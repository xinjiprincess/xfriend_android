package com.singu.xfriend

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class FriendRemoteSession(val origin: String, val cookie: String) {
    override fun toString() = "FriendRemoteSession(origin=$origin, cookie=[redacted])"
}
internal data class FriendRemoteFeatures(
    val cancel: Boolean = false, val approvals: Boolean = false,
    val rooms: Boolean = false, val meetings: Boolean = false, val materials: Boolean = false,
    val roomModelSettings: Boolean = false, val responsibleTasks: Boolean = false,
    val models: Boolean = false, val taskTrees: Boolean = false, val memory: Boolean = false,
    val diagrams: Boolean = false, val documentExports: Boolean = false,
)

internal class FriendRemoteClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private fun request(s: FriendRemoteSession, path: String): Request.Builder {
        require(path.startsWith("/mobile/"))
        val origin = companionOrigin(s.origin)
        require(!s.cookie.contains('\n') && !s.cookie.contains('\r'))
        return Request.Builder().url(origin + path).header("Origin", origin).header("Cookie", s.cookie)
    }
    private suspend fun <T> fetch(request: Request, timeoutSeconds: Long = 30, read: (Response) -> T): T = suspendCancellableCoroutine { c ->
        val call = http.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { val value = response.use(read); if (c.isActive) c.resume(value) }
                catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
            }
        })
    }
    private fun check(response: Response) {
        if (!response.isSuccessful) {
            val value = runCatching {
                val source = response.body?.source() ?: return@runCatching null
                if (source.request(65_537)) return@runCatching null
                JSONObject(source.readUtf8())
            }.getOrNull()
            throw friendRemoteHttpFailure(response.code, value)
        }
    }
    private fun json(response: Response): JSONObject {
        check(response)
        val source = response.body?.source() ?: throw FriendRemoteFailure("protocol", "电脑返回空响应")
        if (source.request(4_000_001)) throw FriendRemoteFailure("protocol", "电脑返回内容过大")
        return JSONObject(source.readUtf8())
    }
    private fun jsonArray(response: Response): org.json.JSONArray {
        check(response)
        val source = response.body?.source() ?: throw FriendRemoteFailure("protocol", "电脑返回空响应")
        if (source.request(4_000_001)) throw FriendRemoteFailure("protocol", "电脑返回内容过大")
        return org.json.JSONArray(source.readUtf8())
    }
    suspend fun pair(address: String, code: String): FriendRemoteSession {
        val origin = companionOrigin(address)
        require(code.trim().length >= 16) { "请输入电脑版显示的完整配对码" }
        val req = Request.Builder().url("$origin/mobile/pair").header("Origin", origin).header("Cookie", "")
            .post(FormBody.Builder().add("code", code.trim()).build()).build()
        return fetch(req) { response ->
            if (response.code != 303 && !response.isSuccessful) throw FriendRemoteFailure(response.code.toString(), "配对失败，请核对电脑地址、配对码与账号（${response.code}）")
            val cookie = Cookie.parseAll(req.url, response.headers).firstOrNull {
                it.name in setOf("xf_mobile", "xfriend_mobile") && it.matches(req.url) && it.expiresAt > System.currentTimeMillis() && it.value.isNotBlank()
            } ?: throw FriendRemoteFailure("pairing", "电脑没有返回有效配对凭据，请升级连接桥")
            FriendRemoteSession(origin, "${cookie.name}=${cookie.value}")
        }
    }
    suspend fun capabilities(s: FriendRemoteSession): FriendRemoteFeatures = fetch(request(s, "/mobile/v2/capabilities").build()) {
        if (it.code == 404) throw FriendRemoteFailure("upgrade_required", "此电脑尚未提供原生执行接口，请升级电脑版及连接桥；可打开电脑工作台")
        if (it.code == 403) throw FriendRemoteFailure("403", "电脑拒绝能力探测，请检查配对账号与授权；旧版连接桥也可能未开放原生接口，请核对电脑版及连接桥版本")
        val value = json(it)
        val cancel = friendRemoteCapabilities(value)
        val features = value.getJSONObject("features")
        FriendRemoteFeatures(cancel, features.optBoolean("approvals"), features.optBoolean("rooms"),
            features.optBoolean("meetings"), features.optBoolean("conversation_materials"),
            features.optBoolean("room_model_settings"), features.optBoolean("responsible_tasks"),
            features.optBoolean("models"), features.optBoolean("task_trees"), features.optBoolean("memory"),
            features.optBoolean("diagrams"), features.optBoolean("document_exports"))
    }
    suspend fun models(s: FriendRemoteSession): List<RemoteModelOption> =
        fetch(request(s, "/mobile/v2/models").build()) { response -> json(response).getJSONArray("models").remoteObjects().map(RemoteModelOption::parse) }
    suspend fun roomSettings(s: FriendRemoteSession, room: String, settings: RemoteRoomSettings): RemoteRoomSettings {
        require(room.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$room/settings").patch(settings.patch().toString().toRequestBody("application/json".toMediaType())).build()) { RemoteRoomSettings.parse(json(it)) }
    }
    suspend fun roomParticipantModel(s: FriendRemoteSession, room: String, participant: String, mode: String, model: String?, revision: Long): JSONObject {
        require(room.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && participant.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$room/participants/$participant")
            .patch(remoteParticipantPatch(mode, model, revision).toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun roomPreflight(s: FriendRemoteSession, room: String, mentions: List<String>): RemoteRoomPreflight {
        require(room.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$room/preflight").post(JSONObject().put("mentions", org.json.JSONArray(mentions)).toString().toRequestBody("application/json".toMediaType())).build()) { response ->
            RemoteRoomPreflight.parse(json(response)).also { require(it.roomId == room) { "模型预检返回了不匹配的房间" } }
        }
    }
    suspend fun responsibleCapabilities(s: FriendRemoteSession): RemoteResponsibleCapabilities =
        fetch(request(s, "/mobile/v2/trees/responsible/capabilities").build()) { RemoteResponsibleCapabilities.parse(json(it)) }
    suspend fun createResponsible(s: FriendRemoteSession, body: JSONObject): JSONObject =
        fetch(request(s, "/mobile/v2/trees/responsible").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    suspend fun resources(s: FriendRemoteSession): List<FriendRemoteResource> = fetch(request(s, "/mobile/v2/resources").build()) {
        val rows = json(it).getJSONArray("resources")
        (0 until rows.length()).map { index -> rows.getJSONObject(index).let { r -> FriendRemoteResource(r.getString("id"), r.getString("name"), r.getString("kind"), r.optString("avatar_path").takeIf { it.startsWith("/mobile/v2/resources/") }) } }
    }
    suspend fun threads(s: FriendRemoteSession, resource: String): List<JSONObject> {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/threads").build()) { response ->
            check(response)
            val rows=jsonArray(response)
            (0 until rows.length()).map{rows.getJSONObject(it)}
        }
    }
    suspend fun threadHistory(s: FriendRemoteSession, resource: String, thread: String): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && thread.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/threads/$thread/history").build(), read=::json)
    }
    suspend fun meetings(s: FriendRemoteSession): List<JSONObject> = fetch(request(s,"/mobile/v2/meetings").build()) { response ->
        check(response)
        val rows=jsonArray(response)
        (0 until rows.length()).map{rows.getJSONObject(it)}
    }
    suspend fun meeting(s: FriendRemoteSession, id: String): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s,"/mobile/v2/meetings/$id").build(),read=::json)
    }
    suspend fun controlMeeting(s: FriendRemoteSession, id: String, action: String, body: JSONObject): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && action in setOf("start","stop","input"))
        return fetch(request(s,"/mobile/v2/meetings/$id/$action").post(body.toString().toRequestBody("application/json".toMediaType())).build(),read=::json)
    }
    suspend fun meetingDefaultHost(s: FriendRemoteSession): JSONObject =
        fetch(request(s, "/mobile/v2/meetings/default-host").build(), read = ::json)
    suspend fun createMeeting(s: FriendRemoteSession, body: JSONObject): JSONObject =
        fetch(request(s, "/mobile/v2/meetings").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    suspend fun meetingFloor(s: FriendRemoteSession, id: String, body: JSONObject): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/meetings/$id/floor").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun meetingAddParticipants(s: FriendRemoteSession, id: String, body: JSONObject): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/meetings/$id/participants").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun roomList(s: FriendRemoteSession): List<JSONObject> =
        fetch(request(s, "/mobile/v2/rooms").build()) { response ->
            check(response)
            val rows = jsonArray(response)
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    suspend fun createRoom(s: FriendRemoteSession, title: String): JSONObject {
        require(title.isNotBlank() && title.length <= 100)
        val body = JSONObject().put("title", title).put("coordination_mode", "leader")
        return fetch(request(s, "/mobile/v2/rooms").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun room(s: FriendRemoteSession, id: String): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$id").build(), read = ::json)
    }
    suspend fun roomParticipants(s: FriendRemoteSession, id: String): List<JSONObject> =
        fetch(request(s, "/mobile/v2/rooms/$id/participants").build()) { response ->
            check(response)
            val rows = jsonArray(response)
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    suspend fun roomAddParticipants(s: FriendRemoteSession, id: String, body: JSONObject): List<JSONObject> {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$id/participants/batch").post(body.toString().toRequestBody("application/json".toMediaType())).build()) { response ->
            check(response)
            val rows = jsonArray(response)
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    }
    suspend fun roomSend(s: FriendRemoteSession, id: String, body: JSONObject): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$id/messages").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun roomFloor(s: FriendRemoteSession, room: String, run: String, body: JSONObject): JSONObject {
        require(room.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && run.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$room/runs/$run/floor").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun roomControl(s: FriendRemoteSession, room: String, run: String, body: JSONObject): JSONObject {
        require(room.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && run.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/rooms/$room/runs/$run/control").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun uploadMaterial(s: FriendRemoteSession, file: java.io.File, filename: String, mediaType: String): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", filename,
            file.asRequestBody(mediaType.toMediaType())).build()
        return fetch(request(s, "/mobile/v2/conversation-materials").post(body).build(), timeoutSeconds = 120, read = ::json)
    }
    suspend fun materialManifest(s: FriendRemoteSession, kind: String, id: String): JSONObject {
        require(kind in setOf("room", "meeting") && id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/conversation-materials/scopes/$kind/$id").build(), read = ::json)
    }
    suspend fun materialDownload(s: FriendRemoteSession, id: String, destination: File) {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        saveResponse(request(s, "/mobile/v2/conversation-materials/$id").build(), destination)
    }
    suspend fun avatar(s: FriendRemoteSession, resource: String): ByteArray {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/avatar").build()) { response ->
            check(response)
            response.body!!.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 5 * 1024 * 1024); output.write(buffer, 0, count) }
                output.toByteArray()
            }
        }
    }
    suspend fun diagram(s: FriendRemoteSession, resource: String, path: String): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        return fetch(request(s, "/mobile/v2/resources/$resource/diagrams/document?path=$encoded").build(), read = ::json)
    }
    suspend fun diagramExport(s: FriendRemoteSession, resource: String, document: JSONObject, format: String, destination: File) {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && format in setOf("mmd", "svg", "png", "pdf"))
        val body = JSONObject().put("document", document).put("format", format)
        saveResponse(request(s, "/mobile/v2/resources/$resource/diagrams/export").post(body.toString().toRequestBody("application/json".toMediaType())).build(), destination)
    }
    suspend fun invitations(s: FriendRemoteSession): List<JSONObject> =
        fetch(request(s, "/mobile/v2/rooms/invitations").build()) { response ->
            check(response)
            val rows = jsonArray(response)
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    suspend fun invitationDecision(s: FriendRemoteSession, id: String, accept: Boolean) {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        fetch(request(s, "/mobile/v2/rooms/invitations/$id/respond").post(JSONObject().put("accept", accept).toString().toRequestBody("application/json".toMediaType())).build()) { check(it) }
    }
    private suspend fun saveResponse(request: Request, destination: File): String =
        fetch(request, timeoutSeconds = 120) { response ->
            check(response)
            val temporary = File.createTempFile("remote-download-", ".part", destination.absoluteFile.parentFile)
            try {
                response.body!!.byteStream().use { input -> temporary.outputStream().use { output -> copyRemoteFile(input, output) } }
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
        }
    suspend fun taskTrees(s: FriendRemoteSession): List<JSONObject> =
        fetch(request(s, "/mobile/v2/trees").build()) { response ->
            check(response)
            val rows = jsonArray(response)
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    suspend fun taskTree(s: FriendRemoteSession, id: String): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/trees/$id").build(), read = ::json)
    }
    suspend fun taskAction(s: FriendRemoteSession, id: String, action: String): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && action in setOf("start", "cancel"))
        return fetch(request(s, "/mobile/v2/trees/$id/$action").post("{}".toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun confirmResponsible(s: FriendRemoteSession, id: String, body: JSONObject): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/trees/$id/confirm").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun answerTaskPermission(s: FriendRemoteSession, tree: String, node: String, body: JSONObject): JSONObject {
        require(tree.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && node.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/trees/$tree/nodes/$node/permission").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun listMemories(s: FriendRemoteSession, resource: String, body: JSONObject): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/memory/atoms/list").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun listMemoryCandidates(s: FriendRemoteSession, resource: String, body: JSONObject): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/memory/candidates/list").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun correctMemory(s: FriendRemoteSession, resource: String, atom: String, body: JSONObject): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && atom.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/memory/atoms/$atom:replace").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun deprecateMemory(s: FriendRemoteSession, resource: String, atom: String, body: JSONObject): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && atom.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/memory/atoms/$atom:deprecate").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun promoteMemoryCandidate(s: FriendRemoteSession, resource: String, candidate: String): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && candidate.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/memory/candidates/$candidate:promote").post("{}".toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun rejectMemoryCandidate(s: FriendRemoteSession, resource: String, candidate: String, body: JSONObject): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && candidate.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/resources/$resource/memory/candidates/$candidate:reject").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun createReport(s: FriendRemoteSession, kind: String, scopeId: String, body: JSONObject): JSONObject {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/$kind/$scopeId/reports").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun reportSourcePreview(s: FriendRemoteSession, kind: String, scopeId: String): JSONObject {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/$kind/$scopeId/document-preview").build(), read = ::json)
    }
    suspend fun listReports(s: FriendRemoteSession, kind: String, scopeId: String): List<JSONObject> {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/$kind/$scopeId/reports").build()) { response ->
            check(response)
            val body = response.body!!.string()
            val rows = if (body.trimStart().startsWith("[")) org.json.JSONArray(body) else JSONObject(body).optJSONArray("reports") ?: org.json.JSONArray()
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    }
    suspend fun getReport(s: FriendRemoteSession, kind: String, scopeId: String, reportId: String): JSONObject {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && reportId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/$kind/$scopeId/reports/$reportId").build(), read = ::json)
    }
    suspend fun cancelReport(s: FriendRemoteSession, kind: String, scopeId: String, reportId: String): JSONObject {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && reportId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/$kind/$scopeId/reports/$reportId/cancel").post("{}".toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun reportPreview(s: FriendRemoteSession, kind: String, scopeId: String, reportId: String): JSONObject {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && reportId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return fetch(request(s, "/mobile/v2/$kind/$scopeId/reports/$reportId/document-preview").build(), read = ::json)
    }
    suspend fun exportReport(s: FriendRemoteSession, kind: String, scopeId: String, reportId: String, body: JSONObject, destination: File): String {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && reportId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return saveResponse(request(s, "/mobile/v2/$kind/$scopeId/reports/$reportId/document").post(body.toString().toRequestBody("application/json".toMediaType())).build(), destination)
    }
    suspend fun exportTranscript(s: FriendRemoteSession, kind: String, scopeId: String, body: JSONObject, destination: File): String {
        require(kind in setOf("meetings", "rooms") && scopeId.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        return saveResponse(request(s, "/mobile/v2/$kind/$scopeId/document").post(body.toString().toRequestBody("application/json".toMediaType())).build(), destination)
    }
    suspend fun submit(s: FriendRemoteSession, run: FriendRemoteRun): JSONObject {
        val body = JSONObject().put("client_request_id", run.clientRequestId).put("resource_id", run.resourceId).put("input", run.input)
        if (run.threadId.isNotEmpty()) body.put("thread_id", run.threadId)
        val attachments = org.json.JSONArray(run.attachmentsJson)
        if (attachments.length() > 0) body.put("attachments", attachments)
        return fetch(request(s, "/mobile/v2/runs").post(body.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    private fun runPath(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}"))) { "电脑任务标识不兼容" }
        return "/mobile/v2/runs/$id"
    }
    suspend fun snapshot(s: FriendRemoteSession, id: String): JSONObject = fetch(request(s, runPath(id)).build(), read = ::json)
    suspend fun upload(s: FriendRemoteSession, resource: String, file: java.io.File, filename: String, mediaType: String): JSONObject {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", filename,
            file.asRequestBody(mediaType.toMediaType())).build()
        return fetch(request(s, "/mobile/v2/resources/$resource/attachments").post(body).build(), timeoutSeconds = 120, read = ::json)
    }
    suspend fun download(s: FriendRemoteSession, resource: String, path: String, destination: java.io.File) {
        require(resource.matches(Regex("[a-zA-Z0-9_-]{1,200}")))
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        saveResponse(request(s, "/mobile/v2/resources/$resource/files?path=$encoded").build(), destination)
    }
    suspend fun logout(s: FriendRemoteSession) = withTimeout(8_000) {
        fetch(request(s, "/mobile/logout").post(byteArrayOf().toRequestBody()).build(), timeoutSeconds = 5) { check(it) }
    }
    suspend fun cancel(s: FriendRemoteSession, id: String) = fetch(request(s, runPath(id) + "/cancel").post("{}".toRequestBody("application/json".toMediaType())).build()) { check(it) }
    suspend fun approval(s: FriendRemoteSession, runId: String, approval: FriendRemoteApproval, decision: String, requestId: String): JSONObject {
        require(!approval.desktopRequired && decision in approval.allowedDecisions && decision in setOf("approve", "reject")) { "此审批必须在电脑版处理" }
        require(requestId.isNotBlank() && requestId.length <= 128)
        val payload = JSONObject().put("client_request_id", requestId).put("summary_hash", approval.summaryHash).put("decision", decision)
        return fetch(request(s, runPath(runId) + "/approvals/" + approval.id)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build(), read = ::json)
    }
    suspend fun events(s: FriendRemoteSession, run: FriendRemoteRun, shouldStop: () -> Boolean = { false }, receive: (JSONObject) -> Unit) {
        fetch(request(s, runPath(run.runId) + "/events?after_seq=${run.cursor}").header("Accept", "text/event-stream").build(), timeoutSeconds = 0) {
            check(it)
            if (it.body?.contentType()?.let { type -> "${type.type}/${type.subtype}" } != "text/event-stream") throw FriendRemoteFailure("protocol", "电脑未返回事件流，请升级连接桥")
            friendRemoteReadEvents(it.body!!.charStream().buffered(), shouldStop, receive)
        }
    }
}
