package com.singu.xfriend

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

internal class FriendRemoteFailure(val code: String, message: String) : IOException(message)
internal fun friendRemoteHttpFailure(status: Int, value: JSONObject?): FriendRemoteFailure {
    val envelope = value?.optJSONObject("error") ?: value?.optJSONObject("detail") ?: value
    val reason = envelope?.optJSONObject("details")?.optString("reason").orEmpty()
    val safeReasons = setOf("thread_busy", "approval_stale", "approval_mismatch", "approval_already_resolved", "desktop_confirmation_required")
    val domain = envelope?.optString("code").orEmpty().ifBlank { envelope?.optString("error_code").orEmpty() }
    val code = when {
        reason.lowercase() in safeReasons -> reason
        domain.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}")) -> domain
        else -> status.toString()
    }
    // Upstream message/details may contain credentials or traces; display only fixed safe copy.
    return FriendRemoteFailure(code, friendRemoteError(code))
}
internal data class FriendRemoteResource(val id: String, val name: String, val kind: String, val avatarPath: String? = null)
internal data class FriendRemoteRun(
    val clientRequestId: String, val resourceId: String, val input: String,
    val runId: String = "", val threadId: String = "", val state: String = "submitting",
    val cursor: Long = 0, val partial: String = "", val error: String = "",
    val approvalJson: String = "", val approvalRequestId: String = "", val approvalDecision: String = "",
    val lastSyncedAt: Long = 0,
    val attachmentsJson: String = "[]",
    val sessionScope: String = "",
) {
    val terminal get() = state in setOf("completed", "done", "failed", "cancelled", "canceled", "interrupted")
    fun json() = JSONObject().put("client_request_id", clientRequestId).put("resource_id", resourceId)
        .put("input", input).put("run_id", runId).put("thread_id", threadId).put("state", state)
        .put("cursor", cursor).put("partial", partial).put("error", error)
        .put("last_synced_at", lastSyncedAt)
        .put("session_scope", sessionScope)
        .put("attachments", org.json.JSONArray(attachmentsJson))
        .put("approval", approvalJson).put("approval_request_id", approvalRequestId).put("approval_decision", approvalDecision)
    val approval get() = approvalJson.takeIf { it.isNotEmpty() }?.let { FriendRemoteApproval.parse(JSONObject(it)) }
    companion object {
        fun parse(j: JSONObject) = FriendRemoteRun(j.getString("client_request_id"), j.getString("resource_id"),
            j.getString("input"), j.optString("run_id"), j.optString("thread_id"), j.optString("state", "submitting"),
            j.optLong("cursor"), j.optString("partial"), j.optString("error"), j.optString("approval"), j.optString("approval_request_id"), j.optString("approval_decision"), j.optLong("last_synced_at"), j.optJSONArray("attachments")?.toString() ?: "[]", j.optString("session_scope"))
    }
}

internal fun friendRemoteCapabilities(j: JSONObject): Boolean {
    if (j.opt("protocol") != 2 || j.optString("application") != "xfriend" || j.optJSONObject("features")?.optBoolean("runs") != true ||
        j.optJSONObject("features")?.optBoolean("event_replay") != true) {
        throw FriendRemoteFailure("upgrade_required", "此电脑尚不支持原生智能体执行，请升级电脑版及连接桥。可返回使用旧电脑模式。")
    }
    return j.getJSONObject("features").optBoolean("cancel")
}

internal fun friendRemoteEvent(run: FriendRemoteRun, event: JSONObject): FriendRemoteRun {
    if (event.getString("run_id") != run.runId) throw FriendRemoteFailure("protocol", "电脑返回了不匹配的任务，请重新连接")
    val seq = event.getLong("seq")
    if (seq <= run.cursor) return run
    if (seq != run.cursor + 1) throw FriendRemoteFailure("event_gap", "任务事件暂不完整，请重连获取已保存进度")
    val payload = event.optJSONObject("payload") ?: JSONObject()
    var next = run.copy(cursor = seq)
    next = when (event.getString("type")) {
        "text_delta" -> next.copy(partial = run.partial + payload.getString("text"))
        "state" -> next.copy(state = payload.getString("state"))
        "approval" -> {
            val approval = FriendRemoteApproval.parse(payload)
            val same = run.approval?.let { it.id == approval.id && it.summaryHash == approval.summaryHash } == true
            next.copy(approvalJson = payload.toString(), approvalRequestId = if (same) run.approvalRequestId else "", approvalDecision = if (same) run.approvalDecision else "")
        }
        "error" -> next.copy(error = friendRemoteError(payload.optString("code")))
        "done" -> next.copy(partial = payload.optString("output").ifEmpty { run.partial }) // End of replay is not proof of success.
        else -> next // Persist tool and future event cursors without inventing results.
    }
    if (next.terminal || (event.optString("type") == "state" && next.state != "awaiting_approval")) {
        next = next.copy(approvalJson = "", approvalRequestId = "", approvalDecision = "")
    }
    if (next.partial.length > 2_000_000) throw FriendRemoteFailure("output_limit", "任务输出过长，请在电脑版查看完整结果")
    return next
}

internal fun friendRemoteError(code: String): String = when (code.lowercase()) {
    "model_configuration_required", "config_model" -> "尚未配置可用模型，请在电脑的模型与账号中设置；草稿和附件已保留"
    "model_auth_required" -> "模型账号授权已失效，请在电脑重新授权；不会自动改用其他账号"
    "model_unavailable", "model_not_found", "team_model_invalid" -> "指定模型当前不可用或你没有权限，请检查对应专家或协调模型设置"
    "model_configuration_invalid" -> "模型配置不完整，请在电脑修正后重试"
    "room_settings_conflict", "settings_revision_conflict", "room_revision_conflict" -> "群设置已被更新，请刷新后重新检查本次修改"
    "room_leader_required", "room_coordinator_required" -> "请先明确选择群负责人或协调模型"
    "422", "slash_bad_args", "invalid_request" -> "提交配置不完整或已变化，请检查选项后重试；未自动改动模型"
    "401", "unauthorized", "session_expired" -> "配对已失效，请在电脑版重新获取配对码"
    "403", "forbidden" -> "电脑拒绝访问，请检查配对账号和授权"
    "404", "not_found", "run_not_found" -> "电脑没有找到该任务或接口，请确认电脑未重置并已升级"
    "429", "rate_limited" -> "电脑当前任务较多，请稍后重连"
    "model_error", "provider_error" -> "电脑模型调用失败，请在电脑版检查模型配置"
    "cancelled", "canceled" -> "任务已取消"
    "interrupted", "incomplete_stream" -> "电脑执行已中断，已有结果已保留，请在电脑版检查任务"
    "approval_required" -> "此任务需要确认，当前原生接口不支持确认操作，请在电脑版处理"
    "approval_stale", "approval_mismatch" -> "电脑待确认操作已改变，请重连后重新查看，未自动批准"
    "approval_already_resolved" -> "该操作已处理，请重连查看任务进度"
    "desktop_confirmation_required" -> "此操作需要在电脑版确认"
    "409", "thread_busy" -> "电脑会话正在执行其他任务或提交内容冲突，请在电脑版检查原任务"
    else -> "电脑任务执行失败，请查看电脑版任务记录后重试"
}

/** SSE data fields are joined per the protocol; incomplete final frames are never committed. */
internal fun friendRemoteReadEvents(reader: BufferedReader, shouldStop: () -> Boolean = { false }, onEvent: (JSONObject) -> Unit) {
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: return
        if (line.length > 2_000_000 || data.length + line.length > 2_000_000) throw FriendRemoteFailure("protocol", "电脑事件超过安全大小，请在电脑版查看")
        if (line.isEmpty()) {
            if (data.isNotEmpty()) { onEvent(JSONObject(data.toString())); data.setLength(0); if (shouldStop()) return }
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).removePrefix(" "))
        }
    }
}
