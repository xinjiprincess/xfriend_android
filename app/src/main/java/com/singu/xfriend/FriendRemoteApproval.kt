package com.singu.xfriend

import org.json.JSONObject

/** Mirrors desktop mobile_runs.models.Approval; a snapshot is authoritative on restore. */
internal data class FriendRemoteApproval(
    val id: String, val summaryHash: String, val actions: String,
    val allowedDecisions: Set<String>, val desktopRequired: Boolean,
) {
    companion object {
        fun parse(value: JSONObject): FriendRemoteApproval {
            val id = value.getString("approval_id")
            val hash = value.getString("summary_hash")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && hash.matches(Regex("[a-fA-F0-9]{64}"))) { "电脑返回的审批标识不兼容，请在电脑版处理" }
            val decisions = value.getJSONArray("allowed_decisions")
            return FriendRemoteApproval(id, hash, value.getJSONArray("actions").toString(2),
                (0 until decisions.length()).map { decisions.getString(it) }.filter { it in setOf("approve", "reject") }.toSet(),
                value.optBoolean("desktop_confirmation_required"))
        }
    }
}

internal fun friendRemoteSnapshot(run: FriendRemoteRun, snapshot: JSONObject): FriendRemoteRun {
    require(snapshot.getString("run_id") == run.runId) { "任务编号不匹配" }
    val approval = snapshot.optJSONObject("approval")
    val parsed = approval?.let(FriendRemoteApproval::parse)
    val same = parsed != null && run.approval?.let { it.id == parsed.id && it.summaryHash == parsed.summaryHash } == true
    val restored = run.copy(state = snapshot.getString("state"), approvalJson = approval?.toString().orEmpty(),
        approvalRequestId = if (same) run.approvalRequestId else "", approvalDecision = if (same) run.approvalDecision else "")
    return if (restored.terminal) restored.copy(
        partial = snapshot.optString("output").ifEmpty { restored.partial },
        error = snapshot.optJSONObject("error")?.let { friendRemoteError(it.optString("code")) }.orEmpty(),
        approvalJson = "", approvalRequestId = "", approvalDecision = "",
    ) else restored
}
