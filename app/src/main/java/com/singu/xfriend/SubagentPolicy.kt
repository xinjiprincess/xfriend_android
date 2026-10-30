package com.singu.xfriend

/** Local execution limits. Reservations are conservative ceilings, not provider billing totals. */
internal object SubagentPolicy {
    const val MAX_CONCURRENT = 3
    const val MAX_DEPTH = 2
    const val MAX_CHILDREN = 8
    // Root requests carry the user's normal conversation and may need several tool rounds.
    // They get a larger budget than a single delegated child, while still being bounded.
    const val ROOT_MAX_MODEL_CALLS = 24
    const val ROOT_TOKEN_BUDGET = 512_000L
    const val MAX_TREE_MODEL_CALLS = 64
    const val MAX_TREE_TOKENS = 1_024_000L
    const val MAX_TASK_MODEL_CALLS = 8
    const val MAX_TASK_TOKENS = 64_000L
    const val ROOT_TIMEOUT_MILLIS = 300_000L
    const val MAX_CHILD_TIMEOUT_MILLIS = 180_000L
    const val MAX_CONTEXT_MESSAGES = 200
    const val MAX_CONTEXT_CHARACTERS = 256_000

    fun validate(run: FriendRun) {
        if (run.contextId.isNotBlank()) FriendRunIds.run(run.contextId)
        if (run.rootContextId.isNotBlank()) FriendRunIds.run(run.rootContextId)
        run.agentParentId?.let(FriendRunIds::run)
        require(run.agentDepth in 0..MAX_DEPTH) { "子代理层级超限" }
        require((run.agentParentId == null) == (run.agentDepth == 0)) { "子代理父任务或层级无效" }
        require(run.agentParentId == null || run.contextId.isNotBlank()) { "子代理上下文缺失" }
        require(run.contextId.isBlank() || run.rootContextId.isBlank()) { "任务不能同时是根上下文与子代理上下文" }
        require(run.agentTitle.length <= 80) { "子代理标题过长" }
        val callLimit = if (run.agentParentId == null) ROOT_MAX_MODEL_CALLS else MAX_TASK_MODEL_CALLS
        val tokenLimit = if (run.agentParentId == null) ROOT_TOKEN_BUDGET else MAX_TASK_TOKENS
        require(run.maxModelCalls in 1..callLimit && run.modelCallsUsed in 0..callLimit)
        require(run.tokenBudget in 1..tokenLimit && run.tokensReserved in 0..tokenLimit)
        require(run.deadlineAt >= 0 && run.agentTimeoutMillis in 0..MAX_CHILD_TIMEOUT_MILLIS)
        require(run.modelReservations.size <= MAX_TREE_MODEL_CALLS && run.modelReservations.values.all { it in 1..tokenLimit })
        require(run.modelCallsUsed == run.modelReservations.size && run.tokensReserved == run.modelReservations.values.sum()) {
            "模型预算记录不一致"
        }
        require(run.contextId.isNotBlank() || run.rootContextId.isNotBlank() || (run.agentMessages.isEmpty() && run.agentHistorySteps.isEmpty()))
        validateContext(run.resourceId, run.agentMessages, run.agentHistorySteps)
    }

    fun validateContext(resourceId: String, messages: Map<String, List<Message>>, historySteps: Set<String>) {
        require(messages.keys.all { it == resourceId }) { "子代理上下文目标无效" }
        val rows = messages.values.flatten()
        require(rows.size <= MAX_CONTEXT_MESSAGES && rows.sumOf { it.content.length.toLong() + it.replyToPreview.length } <= MAX_CONTEXT_CHARACTERS) { "子代理上下文超限" }
        require(rows.all { it.role in setOf("user", "assistant") && it.id.length <= 160 && it.replyToId.length <= 160 })
        require(rows.filter { it.id.isNotBlank() }.map { it.id }.distinct().size == rows.count { it.id.isNotBlank() }) { "子代理消息编号重复" }
        require(historySteps.size <= 4096 && historySteps.all { it.isNotBlank() && it.length <= 256 })
    }
}
