package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val REMOTE_FILE_LIMIT = 100L * 1024 * 1024

internal fun remotePairedStorageScope(account: String, session: FriendRemoteSession): String =
    MessageDigest.getInstance("SHA-256").digest((account + "\u0000" + friendRemoteSessionScope(session)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun copyRemoteFile(input: InputStream, output: OutputStream, limit: Long = REMOTE_FILE_LIMIT) {
    val buffer = ByteArray(65536)
    var size = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        size += count
        require(size <= limit) { "文件超过允许大小，请在电脑保存" }
        output.write(buffer, 0, count)
    }
}

internal fun JSONObject.remoteNullable(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONArray.remoteObjects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal data class RemoteModelOption(val ref: String, val label: String, val provider: String, val model: String) {
    companion object {
        fun parse(value: JSONObject): RemoteModelOption {
            val ref = value.getString("ref")
            require(ref.isNotBlank() && ref.length <= 300)
            return RemoteModelOption(ref, value.optString("label", ref), value.optString("provider"), value.optString("model"))
        }
    }
}

internal data class RemoteRoomSettings(
    val mode: String, val leader: String?, val model: String?, val revision: Long,
) {
    companion object {
        fun parse(value: JSONObject): RemoteRoomSettings {
            val mode = value.optString("coordination_mode", "legacy")
            require(mode in setOf("legacy", "leader", "model", "manual")) { "电脑返回了不兼容的协调方式" }
            return RemoteRoomSettings(mode, value.remoteNullable("leader_participant_id"), value.remoteNullable("coordinator_model"), value.optLong("settings_revision"))
        }
    }
    fun patch(): JSONObject {
        require(mode != "leader" || !leader.isNullOrBlank()) { "请明确选择群负责人" }
        require(mode != "model" || !model.isNullOrBlank()) { "请选择协调模型" }
        return JSONObject().put("coordination_mode", mode)
            .put("leader_participant_id", if (mode == "leader") leader else JSONObject.NULL)
            .put("coordinator_model", if (mode in setOf("model", "legacy")) model ?: JSONObject.NULL else JSONObject.NULL)
            .put("expected_revision", revision)
    }
}

internal fun remoteParticipantPatch(mode: String, model: String?, revision: Long): JSONObject {
    require(mode in setOf("inherit", "override"))
    require(mode != "override" || !model.isNullOrBlank()) { "请选择本群席位模型" }
    return JSONObject().put("model_mode", mode).put("model", if (mode == "override") model else JSONObject.NULL)
        .put("expected_revision", revision)
}

internal data class RemoteModelCheck(
    val role: String, val participantId: String?, val name: String, val model: String?,
    val source: String, val ready: Boolean, val errorCode: String?,
) {
    val description: String get() = "$name · ${model ?: "未配置模型"} · $source" +
        if (ready) " · 可用" else " · " + friendRemoteError(errorCode ?: "MODEL_CONFIGURATION_REQUIRED")
    companion object {
        fun parse(value: JSONObject) = RemoteModelCheck(value.getString("role"), value.remoteNullable("participant_id"),
            value.optString("name", value.optString("role")), value.remoteNullable("model"), value.optString("source"),
            value.getBoolean("ready"), value.remoteNullable("error_code"))
    }
}

internal data class RemoteRoomPreflight(
    val roomId: String, val ready: Boolean, val mode: String, val revision: Long,
    val coordinator: RemoteModelCheck?, val participants: List<RemoteModelCheck>,
) {
    val checks: List<RemoteModelCheck> get() = listOfNotNull(coordinator) + participants
    companion object {
        fun parse(value: JSONObject) = RemoteRoomPreflight(value.getString("room_id"), value.getBoolean("ready"),
            value.getString("coordination_mode"), value.getLong("settings_revision"),
            value.optJSONObject("coordinator")?.let(RemoteModelCheck::parse), value.getJSONArray("participants").remoteObjects().map(RemoteModelCheck::parse))
    }
}

internal data class RemoteMaterial(
    val id: String, val filename: String, val size: Long, val status: String,
    val contentStatus: String, val contentReason: String,
) {
    val readiness: String get() = when (contentStatus) {
        "ready" -> "内容可读"
        "partial" -> "部分内容可读"
        "unreadable" -> "暂无可读内容"
        "processing", "pending" -> "内容处理中"
        else -> "内容可读性未知"
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("filename", filename).put("size", size)
        .put("status", status).put("content_status", contentStatus).put("content_reason", contentReason)
    companion object {
        fun parse(value: JSONObject) = RemoteMaterial(value.getString("id"), value.optString("filename", "资料"),
            value.optLong("size"), value.optString("status"), value.optString("content_status", "unknown"), value.optString("content_reason"))
    }
}

internal fun remoteRoomMessage(text: String, requestId: String, materials: List<String>, mentions: List<String>): JSONObject {
    require((text.isNotBlank() || materials.isNotEmpty()) && text.length <= 32000) { "请输入消息或选择已上传的资料，消息最多32000字" }
    require(requestId.isNotBlank() && requestId.length <= 100 && materials.size <= 20 && mentions.size <= 20)
    return JSONObject().put("text", text).put("idempotency_key", requestId)
        .put("material_ids", JSONArray(materials.distinct())).put("mentions", JSONArray(mentions.distinct()))
}

internal data class RemoteResponsibleExpert(val id: String, val name: String, val model: String?)
internal data class RemoteResponsibleCapabilities(val experts: List<RemoteResponsibleExpert>, val models: List<String>) {
    companion object {
        fun parse(value: JSONObject) = RemoteResponsibleCapabilities(value.getJSONArray("experts").remoteObjects().map {
            RemoteResponsibleExpert(it.getString("expert_id"), it.getString("name"), it.remoteNullable("default_model"))
        }, value.getJSONArray("models").remoteObjects().map { it.getString("model_ref") })
    }
}

internal fun remoteResponsibleRequest(requestId: String, goal: String, leader: String?, model: String?, experts: List<String>, materials: List<String>): JSONObject {
    require(requestId.isNotBlank() && requestId.length <= 100)
    require(goal.isNotBlank() && goal.length <= 16000) { "请输入1–16000字的任务目标" }
    require(!leader.isNullOrBlank() || !model.isNullOrBlank()) { "请选择负责人专家或独立模型" }
    require(experts.size <= 100 && materials.size <= 20) { "最多选择100位专家和20份资料" }
    return JSONObject().put("request_id", requestId).put("goal", goal)
        .put("leader_expert_id", leader ?: JSONObject.NULL).put("leader_model", model ?: JSONObject.NULL)
        .put("allowed_expert_ids", JSONArray(experts.distinct())).put("material_ids", JSONArray(materials.distinct()))
}
