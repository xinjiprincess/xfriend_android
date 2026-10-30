package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One instance per task/account. Save must atomically commit a detached JSON object before returning.
 * The service must encrypt this sensitive conversation data at rest; never log it. No API keys are
 * serialized here. A failed save must abort execution, not be treated as a successful checkpoint.
 * Resume continues from complete model/tool boundaries, never from a partial HTTP generation.
 */
interface RuntimeCheckpoint {
    suspend fun load(): JSONObject?
    suspend fun save(checkpoint: JSONObject)
}

internal fun runtimeHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun runtimeCanonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + runtimeCanonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { runtimeCanonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/** A screen tree is ephemeral evidence: only a stale marker may cross a process boundary. */
internal fun runtimeCheckpointForStorage(data: JSONObject): JSONObject {
    val detached = JSONObject(data.toString())
    val steps = detached.optJSONObject("steps") ?: return detached
    steps.keys().asSequence().toList().forEach { key ->
        val conversation = steps.optJSONObject(key)?.optJSONArray("conversation") ?: return@forEach
        val screenReads = mutableSetOf<String>()
        for (i in 0 until conversation.length()) {
            val message = conversation.optJSONObject(i) ?: continue
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                val call = calls.optJSONObject(j) ?: continue
                if (call.optJSONObject("function")?.optString("name") == "phone_read_ui") screenReads += call.optString("id")
            }
        }
        for (i in 0 until conversation.length()) {
            val message = conversation.optJSONObject(i) ?: continue
            if (message.optString("role") == "tool" && message.optString("tool_call_id") in screenReads)
                message.put("content", JSONObject().put("status", "stale_snapshot")
                    .put("message", "屏幕快照未保存，请重新读取当前界面；旧节点和快照不可重用。").toString())
        }
    }
    return detached
}

internal class RuntimeProgress private constructor(private val backend: RuntimeCheckpoint, val data: JSONObject) {
    val runId: String get() = data.getString("run_id")
    suspend fun persist() = backend.save(runtimeCheckpointForStorage(data))
    fun step(key: String): JSONObject = data.getJSONObject("steps").let { steps ->
        steps.optJSONObject(key) ?: JSONObject().put("round", 0).put("tool_index", 0).also { steps.put(key, it) }
    }
    suspend fun reviewRequired(): Nothing {
        data.put("status", "requires_review"); persist()
        throw FriendModelFailure("CHECKPOINT_REVIEW", "上次工具操作的结果无法确认，请先人工核查后重新发起，不能自动重放")
    }
    companion object {
        suspend fun open(backend: RuntimeCheckpoint?, target: String, team: Boolean, input: String, fingerprint: String, mode: String = ConversationMode.CRAFT): RuntimeProgress? {
            if (backend == null) return null
            val old = backend.load()?.let { JSONObject(it.toString()) }
            val data = old ?: JSONObject().put("version", 1).put("run_id", UUID.randomUUID().toString())
                .put("target", target).put("team", team).put("input_hash", runtimeHash(input)).put("fingerprint", fingerprint)
                .put("conversation_mode", ConversationMode.requireValid(mode))
                .put("steps", JSONObject()).put("team_index", 0).put("trace", JSONArray()).put("status", "running")
            if (data.optInt("version") != 1 || data.optString("target") != target || data.optBoolean("team") != team || data.optString("input_hash") != runtimeHash(input))
                throw FriendModelFailure("CHECKPOINT_INPUT_CHANGED", "任务输入与检查点不一致，请重新发起任务")
            if (data.optString("conversation_mode", ConversationMode.CRAFT) != mode)
                throw FriendModelFailure("CHECKPOINT_MODE_CHANGED", "任务模式与检查点不一致，请重新发起任务")
            if (data.optString("fingerprint") != fingerprint)
                throw FriendModelFailure("CHECKPOINT_CONFIG_CHANGED", "模型、专家或工具配置已变化，请重新发起任务")
            if (data.optString("status") == "requires_review")
                throw FriendModelFailure("CHECKPOINT_REVIEW", "上次工具操作需要人工检查，请核查结果后重新发起任务")
            return RuntimeProgress(backend, data).also { if (old == null) it.persist() }
        }
    }
}

internal fun checkpointWrittenFile(root: File, pending: JSONObject): Boolean = try {
    val file = workspacePath(root, pending.getString("path"))
    if (!file.isFile || file.length() > 65536) false
    else {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    digest == pending.getString("sha256")
    }
} catch (_: Exception) { false }
