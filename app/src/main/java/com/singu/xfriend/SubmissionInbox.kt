package com.singu.xfriend

/**
 * Visible receipt for one submitted request. The draft is cleared immediately, but a failed
 * preflight never silently loses the text: the entry keeps the original input and a retry action.
 */
internal data class InboxEntry(
    val requestId: String,
    val input: String,
    val resourceId: String,
    val team: Boolean,
    val state: String = "已接收",
    val message: String = "",
    val runId: String = "",
    val retryable: Boolean = false,
)

internal object SubmissionInbox {
    fun upsert(entries: List<InboxEntry>, entry: InboxEntry): List<InboxEntry> =
        entries.filterNot { it.requestId == entry.requestId } + entry

    fun mergeRuns(entries: List<InboxEntry>, runs: List<FriendRun>): List<InboxEntry> {
        if (runs.isEmpty()) return entries
        return entries.map { entry ->
            val run = runs.firstOrNull { it.clientRequestId == entry.requestId || it.requestMessageId == entry.requestId }
                ?: return@map entry
            entry.copy(
                runId = run.id,
                state = if (run.active) (if (run.state == "queued") "排队中" else "执行中") else when (run.state) {
                    "completed" -> "已完成"
                    "requires_review" -> "待核查"
                    "cancelled" -> "已停止"
                    else -> "未完成"
                },
                message = run.errorMessage.take(200),
                retryable = !run.active && run.state != "completed",
            )
        }
    }
}
