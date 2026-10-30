package com.singu.xfriend

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal const val SALES_CLOUD_CHUNK = 262144
internal fun salesCloudMediaId(id: String): String {
    require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) { "无效云端媒体标识" }
    return id
}
internal fun salesCloudChunkLength(size: Long, index: Int): Int {
    require(size in 1..104857600L && index >= 0 && index.toLong() * SALES_CLOUD_CHUNK < size) { "无效媒体分块" }
    return minOf(SALES_CLOUD_CHUNK.toLong(), size - index.toLong() * SALES_CLOUD_CHUNK).toInt()
}
internal fun salesCloudMediaManifest(j: JSONObject, id: String, checksum: String = ""): JSONObject {
    require(j.getString("media_id") == salesCloudMediaId(id)) { "媒体标识不匹配" }
    val size = j.getLong("total_size")
    require(size in 1..104857600L && j.getInt("chunk_size") == SALES_CLOUD_CHUNK && j.getInt("chunk_count") == ((size + SALES_CLOUD_CHUNK - 1) / SALES_CLOUD_CHUNK).toInt()) { "媒体大小或分块数量错误" }
    require(j.getString("sha256").matches(Regex("[a-f0-9]{64}")) && (checksum.isBlank() || checksum == j.getString("sha256"))) { "媒体校验值不匹配" }
    j.optJSONArray("uploaded_chunks")?.let { chunks ->
        for (i in 0 until chunks.length()) salesCloudChunkLength(size, chunks.getInt(i))
    }
    return j
}
internal fun salesCloudFileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(SALES_CLOUD_CHUNK)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Shared by the Android transfer and deterministic fake-server tests. */
internal suspend fun salesCloudUploadChunks(file: File, manifest: JSONObject, send: suspend (Int, ByteArray) -> Unit) {
    val size = manifest.getLong("total_size")
    require(file.length() == size && salesCloudFileHash(file) == manifest.getString("sha256")) { "待上传媒体已修改" }
    val uploaded = manifest.optJSONArray("uploaded_chunks") ?: org.json.JSONArray()
    java.io.RandomAccessFile(file, "r").use { source ->
        for (index in 0 until manifest.getInt("chunk_count")) {
            if ((0 until uploaded.length()).any { uploaded.getInt(it) == index }) continue
            val chunk = ByteArray(salesCloudChunkLength(size, index))
            source.seek(index.toLong() * SALES_CLOUD_CHUNK); source.readFully(chunk)
            send(index, chunk)
        }
    }
    require(salesCloudFileHash(file) == manifest.getString("sha256")) { "上传期间媒体被修改" }
}

internal suspend fun salesCloudDownloadChunks(file: File, manifest: JSONObject, downloaded: org.json.JSONArray,
    fetch: suspend (Int) -> ByteArray, checkpoint: () -> Unit) {
    java.io.RandomAccessFile(file, "rw").use { target ->
        target.setLength(manifest.getLong("total_size"))
        for (index in 0 until manifest.getInt("chunk_count")) {
            if ((0 until downloaded.length()).any { downloaded.getInt(it) == index }) continue
            val chunk = fetch(index)
            require(chunk.size == salesCloudChunkLength(manifest.getLong("total_size"), index)) { "下载分块长度错误" }
            target.seek(index.toLong() * SALES_CLOUD_CHUNK); target.write(chunk); target.fd.sync()
            downloaded.put(index); checkpoint()
        }
    }
    require(salesCloudFileHash(file) == manifest.getString("sha256")) { "媒体完整校验失败，不能关联回放" }
}

/** A bad attachment must not abort the remaining attachments or CRM checkpointing. */
internal suspend fun salesCloudMediaAttempt(label: String, warnings: MutableList<String>, action: suspend () -> Unit) {
    try { action() }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (failure: Exception) { warnings.add("$label：${failure.message ?: "媒体处理失败"}") }
}
internal fun salesCloudMediaSize(size: Long) {
    require(size in 1..104857600L) { "媒体须为1字节至100MiB，已跳过" }
}

/** Saved before transfer and retained across retries, so a retry cannot undo a user's removal. */
internal data class SalesCloudMediaLinks(val file: String, val attachments: List<String>) {
    fun json() = JSONObject().put("file", file).put("attachments", org.json.JSONArray(attachments))
    fun associate(current: SalesCloudMediaLinks?, local: String, primary: Boolean): SalesCloudMediaLinks? {
        if (current != this) return null
        if (local == file || local in attachments) return current
        return if (primary && file.isBlank()) copy(file = local) else copy(attachments = attachments + local)
    }
    companion object {
        fun read(j: JSONObject): SalesCloudMediaLinks {
            val attachments = j.getJSONArray("attachments")
            return SalesCloudMediaLinks(j.getString("file"), (0 until attachments.length()).map { attachments.getString(it) })
        }
    }
}
