package com.singu.xfriend

/** Pure commit merge for an isolated root run. Replies bind to the request message id. */
internal object RootTurnMerge {
    fun merge(turns: List<Message>, run: FriendRun): List<Message> {
        if (run.rootContextId.isBlank() || run.agentParentId != null) return turns
        if (run.state != "completed" || run.partial.isBlank()) return turns
        val result = turns.toMutableList()
        val requestId = run.requestMessageId.ifBlank { "${run.id}:user" }
        if (result.none { it.id == requestId }) {
            result += Message("user", run.input, requestId, run.replyToId, run.replyToRole, run.replyPreview)
        }
        val answerId = "${run.id}:assistant"
        if (result.none { it.id == answerId }) {
            result += Message("assistant", run.partial, answerId, requestId, "user", run.input.take(140))
        }
        return result.takeLast(200)
    }
}
