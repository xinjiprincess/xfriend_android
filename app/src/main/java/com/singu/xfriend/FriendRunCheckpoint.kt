package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject

// RuntimeCheckpoint is owned by AgentRuntimeCheckpoint.kt. Its suspend load/save complete only
// after durable encrypted I/O. This adapter uses its real status + steps[*].pending wire contract.

/** Runtime can throw this instead of guessing whether an external operation already happened. */
class FriendRunNeedsReview : Exception("工具操作结果需要人工确认")

internal fun friendCheckpointNeedsReview(checkpoint: String?): Boolean {
    if (checkpoint == null) return false
    val value = JSONObject(checkpoint)
    val steps = value.optJSONObject("steps")
    val pending = steps?.keys()?.asSequence()?.any { key -> steps.optJSONObject(key)?.optJSONObject("pending") != null } == true
    return pending || value.optString("status") == "requires_review" ||
        value.optString("phase") in setOf("pending_effect", "requires_review") || value.optBoolean("requires_review")
}

/** Only native runtime's write_file pending records have a read-only hash verification path. */
internal fun friendCheckpointCanVerify(checkpoint: String?): Boolean = checkpoint?.let { text ->
    val value = JSONObject(text)
    val steps = value.optJSONObject("steps") ?: return@let false
    val pending = steps.keys().asSequence().mapNotNull { steps.optJSONObject(it)?.optJSONObject("pending") }.toList()
    value.optInt("version") == 1 && value.optString("status") in setOf("running", "requires_review") && pending.isNotEmpty() &&
        pending.all { it.optString("path").isNotBlank() && it.optString("call_id").isNotBlank() && it.optString("sha256").matches(Regex("[a-f0-9]{64}")) }
} ?: false

/** Structural credential guard. Runtime must also keep provider config outside its JSON entirely.
 * Strings from user/tool content aren't rewritten: censoring those would corrupt recovery evidence.
 */
internal fun validateFriendCheckpoint(checkpoint: JSONObject): String {
    fun inspect(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val normalized = key.lowercase().replace("_", "").replace("-", "")
                require(normalized !in setOf("apikey", "accesskey", "secretkey", "accesstoken", "refreshtoken", "authorization", "password", "credentials", "portablesecrets")) {
                    "检查点不得保存模型凭据"
                }
                inspect(value.opt(key))
            }
            is JSONArray -> (0 until value.length()).forEach { inspect(value.opt(it)) }
        }
    }
    inspect(checkpoint)
    require(checkpoint.optString("status") in setOf("running", "requires_review", "completed") ||
        checkpoint.optString("phase") in setOf("ready", "pending_model", "pending_effect", "requires_review", "completed")) { "检查点阶段无效" }
    require(checkpoint.optInt("version") >= 1) { "检查点版本无效" }
    return checkpoint.toString()
}

internal class FriendRunRuntimeCheckpoint(
    private val queue: FriendRunQueue,
    private val runId: String,
    private val authorized: () -> Boolean,
) : RuntimeCheckpoint {
    private fun guard() {
        if (!authorized() || queue.runs().none { it.id == runId && it.state == "running" })
            throw kotlinx.coroutines.CancellationException("authorization_ended")
    }
    override suspend fun load(): JSONObject? {
        guard()
        val run = queue.runs().first { it.id == runId }
        if (friendCheckpointNeedsReview(run.checkpoint)) {
            if (!run.verifyRequested || !friendCheckpointCanVerify(run.checkpoint)) throw FriendRunNeedsReview()
            // Explicit user verification ONLY. Keep pending intact so runtime must hash-check and
            // skip a confirmed write, or throw CHECKPOINT_REVIEW before writing if it cannot verify.
            return JSONObject(run.checkpoint!!).put("status", "running")
        }
        return run.checkpoint?.let(::JSONObject)
    }
    override suspend fun save(checkpoint: JSONObject) {
        guard()
        if (!queue.saveCheckpoint(runId, validateFriendCheckpoint(checkpoint)))
            throw kotlinx.coroutines.CancellationException("cancelled")
    }
}
