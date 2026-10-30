package com.singu.xfriend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Network transfers never hold Store/CRM locks. Only checkpoints and final associations do. */
internal class SalesCloudSyncMedia(private val context: Context, private val storage: SalesCloudSyncStorage, private val account: CloudAccount) {
    val warnings = mutableListOf<String>()
    private val identity = storage.identity
    private val repo = SalesRepository(context, identity.scope)
    private val query = mapOf("space_id" to identity.space)
    private val ledger = storage.mediaLedger()
    private val staging = File(context.noBackupFilesDir, "sales/${identity.scope}/cloud-media").apply { mkdirs() }
    private fun checkpoint() { checkScope(); storage.saveMediaLedger(ledger) }
    private fun checkScope() { require(account.scope == identity.scope) { "账号或空间已切换，媒体同步已停止" } }
    private suspend fun get(path: String): JSONObject { checkScope(); return account.authorizedGet(path, query, identity.scope).also { checkScope() } }
    private suspend fun post(path: String, json: JSONObject): JSONObject { checkScope(); return account.authorizedPost(path, json, identity.scope).also { checkScope() } }
    private suspend fun bytes(path: String, data: ByteArray? = null): ByteArray {
        currentCoroutineContext().ensureActive(); checkScope()
        return account.authorizedMedia(path, if (data == null) "GET" else "PUT", data, query, identity.scope).also { checkScope() }
    }
    suspend fun upload() {
        val names = repo.load().records.flatMap { it.attachments + it.file }.filter { it.isNotBlank() }.distinct()
        for (name in names) {
            currentCoroutineContext().ensureActive(); checkScope()
            salesCloudMediaAttempt("上传 $name", warnings) {
            val file = repo.file(name)
            require(file.isFile) { "本地媒体不存在，已跳过" }
            salesCloudMediaSize(file.length())
            val hash = salesCloudFileHash(file)
            val existing = ledger.keys().asSequence().firstOrNull { id -> ledger.getJSONObject(id).let { it.optString("local") == name && it.optString("local_sha256") == hash } }
            if (existing != null && ledger.getJSONObject(existing).optBoolean("complete")) return@salesCloudMediaAttempt
            val id = existing ?: UUID.randomUUID().toString()
            val entry = if (existing != null) ledger.getJSONObject(id) else JSONObject().put("local", name).put("local_sha256", hash)
                .put("manifest", JSONObject().put("media_id", id).put("filename", name).put("sha256", hash).put("total_size", file.length())
                    .put("content_type", when (file.extension) { "jpg" -> "image/jpeg"; "mp3" -> "audio/mpeg"; else -> "audio/wav" }))
            ledger.put(id, entry); checkpoint() // Stable ID is durable before the first network request.
            val request = JSONObject(entry.getJSONObject("manifest").toString())
            listOf("upload_id", "status", "chunk_size", "chunk_count", "uploaded_chunks").forEach(request::remove)
            request.put("space_id", identity.space).put("device_id", identity.device)
            var manifest = salesCloudMediaManifest(post("/api/v2/sync/media/uploads", request), id, hash)
            require(manifest.getLong("total_size") == file.length()) { "媒体长度发生变化" }
            val upload = salesCloudMediaId(manifest.getString("upload_id"))
            salesCloudUploadChunks(file, manifest) { index, chunk ->
                val ack = JSONObject(String(bytes("/api/v2/sync/media/uploads/$upload/chunks/$index", chunk), Charsets.UTF_8))
                require(ack.getInt("chunk_index") == index && ack.getString("status") in setOf("applied", "duplicate")) { "媒体分块未确认" }
            }
            manifest = salesCloudMediaManifest(post("/api/v2/sync/media/uploads/$upload/complete", JSONObject().put("space_id", identity.space)), id, hash)
            require(manifest.getString("status") == "complete") { "媒体尚未完成上传" }
            entry.put("manifest", manifest).put("complete", true); checkpoint()
            }
            checkScope()
        }
    }
    suspend fun download() {
        val state = storage.load()
        for (entity in state.known.values.filter { it.type == "communication" && !it.deleted }) {
            if (state.activeConflicts.any { it.local.key == entity.key }) continue
            if (repo.load().records.none { it.id == entity.id }) continue
            val refs = JSONObject(entity.payload).optJSONArray("attachments") ?: continue
            for (index in 0 until refs.length()) {
                val reference = refs.optJSONObject(index) ?: continue
                val remoteId = reference.optString("media_id").takeIf { it.isNotBlank() } ?: continue
                currentCoroutineContext().ensureActive(); checkScope()
                salesCloudMediaAttempt("下载 $remoteId", warnings) {
                val id = salesCloudMediaId(remoteId)
                val before = repo.load().records.find { it.id == entity.id } ?: return@salesCloudMediaAttempt
                val entry = ledger.optJSONObject(id) ?: JSONObject().put("temporary", UUID.randomUUID().toString()).put("downloaded", JSONArray()).also {
                    ledger.put(id, it); checkpoint()
                }
                val associations = entry.optJSONObject("associations") ?: JSONObject().also { entry.put("associations", it) }
                val referenceKey = salesCloudCanonical(reference)
                val association = associations.optJSONObject(entity.id)?.takeIf { it.optString("reference") == referenceKey }
                    ?: JSONObject().put("reference", referenceKey).put("snapshot", SalesCloudMediaLinks(before.file, before.attachments).json()).also {
                        associations.put(entity.id, it); checkpoint()
                    }
                if (association.optBoolean("skipped")) {
                    warnings.add("下载 $id：附件关联已被修改，保留当前附件")
                    return@salesCloudMediaAttempt
                }
                val expected = SalesCloudMediaLinks.read(association.getJSONObject("snapshot"))
                var local = entry.optString("local")
                if (!entry.optBoolean("complete") || local.isBlank() || !repo.file(local).isFile || salesCloudFileHash(repo.file(local)) != entry.optString("local_sha256")) {
                    val manifest = salesCloudMediaManifest(get("/api/v2/sync/media/$id"), id, reference.optString("sha256"))
                    require(manifest.getString("status") == "complete") { "媒体尚未完成上传" }
                    val temporary = entry.optString("temporary").ifBlank { UUID.randomUUID().toString().also { entry.put("temporary", it) } }
                    require(temporary.matches(Regex("[a-f0-9-]{36}")))
                    val part = File(staging, "$temporary.part")
                    if (!part.exists()) entry.put("downloaded", JSONArray())
                    val downloaded = entry.optJSONArray("downloaded") ?: JSONArray()
                    entry.put("manifest", manifest).put("downloaded", downloaded); checkpoint()
                    try {
                        salesCloudDownloadChunks(part, manifest, downloaded,
                            fetch = { chunkIndex -> bytes("/api/v2/sync/media/$id/chunks/$chunkIndex") }, checkpoint = ::checkpoint)
                    } catch (failure: IllegalArgumentException) {
                        entry.put("downloaded", JSONArray()); checkpoint()
                        throw failure
                    }
                    local = playable(part, manifest.getString("content_type"))
                    entry.put("local", local).put("local_sha256", salesCloudFileHash(repo.file(local))).put("complete", true); checkpoint()
                    part.delete()
                }
                checkScope()
                var associated: SalesCloudMediaLinks? = null
                repo.update { data -> data.copy(records = data.records.map { record ->
                    if (record.id != entity.id) record else {
                        val links = expected.associate(SalesCloudMediaLinks(record.file, record.attachments), local, reference.optBoolean("primary"))
                        associated = links
                        if (links == null) record else record.copy(file = links.file, attachments = links.attachments)
                    }
                }) }
                if (associated == null) {
                    association.put("skipped", true)
                    warnings.add("下载 $id：附件关联已被修改，保留当前附件")
                } else association.put("snapshot", associated!!.json())
                checkpoint()
                }
                checkScope()
            }
        }
    }
    private fun playable(original: File, mime: String): String {
        val image = mime.startsWith("image/")
        require(image || mime.startsWith("audio/")) { "此媒体类型暂不支持本地回放：$mime" }
        val name = UUID.randomUUID().toString() + if (image) ".jpg" else ".wav"
        val target = repo.file(name)
        try {
            if (image) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(original.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
                val options = BitmapFactory.Options().apply { while (bounds.outWidth / inSampleSize > 4096 || bounds.outHeight / inSampleSize > 4096) inSampleSize *= 2 }
                val bitmap = BitmapFactory.decodeFile(original.path, options) ?: error("图片无法解码")
                try { target.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } } finally { bitmap.recycle() }
            } else decodeAudio(context, Uri.fromFile(original), target)
            require(target.isFile && target.length() > 0) { "媒体转换失败" }
            return name
        } catch (failure: Exception) { target.delete(); throw failure }
    }
}
