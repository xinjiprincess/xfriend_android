package com.singu.xfriend

/** All read/modify/write operations share a process-wide scope lock supplied by the repository. */
internal interface FriendRunStorage {
    fun read(): List<FriendRun>
    fun write(runs: List<FriendRun>)
}

internal class FriendRunQueue(private val storage: FriendRunStorage, private val lock: Any,
    private val clock: () -> Long = { System.currentTimeMillis() }) {
    fun runs(): List<FriendRun> = synchronized(lock) { storage.read().toList() }

    fun recoverStale(scope: String) = synchronized(FriendRunLiveness.lock) {
        if (!FriendRunLiveness.active(scope)) recover()
    }

    fun recover() = synchronized(lock) {
        val before = storage.read()
        val now = clock()
        val after = before.map { run ->
            when {
                run.active && cancellationFenced(run, before) -> cancelled(run, "parent_cancelled", true, now)
                run.active && expired(run, before, now) -> expiredRun(run, now)
                run.state == "running" -> {
                    val review = friendCheckpointNeedsReview(run.checkpoint)
                    run.copy(state = if (review) "requires_review" else "interrupted",
                        errorCode = if (review) "tool_effect_uncertain" else "process_interrupted", diagnosticId = FriendRunIds.newId(),
                        errorMessage = if (review) "工具操作结果不确定，请核查后处理，不能自动重放。" else if (run.checkpoint != null) "上次运行已中断，可从已保存检查点继续。" else "上次运行已中断；重新运行会创建新任务，可能再次执行工具。",
                        updated = now)
                }
                else -> run
            }
        }
        if (after != before) storage.write(after)
    }

    fun enqueue(resourceId: String, input: String, team: Boolean, clientRequestId: String, parentId: String? = null, conversationMode: String = ConversationMode.CRAFT, localRoomTurn: LocalRoomTurn? = null, reply: StarReply? = null,
        requestMessageId: String = "", historySnapshot: List<Message>? = null): FriendRun = synchronized(lock) {
        ConversationMode.requireValid(conversationMode)
        FriendRunIds.resource(resourceId); FriendRunIds.request(clientRequestId)
        require(input.isNotBlank()) { "请输入任务内容" }
        require(requestMessageId.length <= 160)
        val group = localRoomTurn?.let { turn ->
            require(turn.roomId == resourceId && !team && input.length <= 12000) { "群任务目标或内容无效" }
            turn.copy(memberIds = turn.memberIds.toList())
        }
        val items = storage.read()
        items.firstOrNull { it.clientRequestId == clientRequestId }?.let {
            require(it.resourceId == resourceId && it.input == input && it.team == team && it.parentId == parentId && it.conversationMode == conversationMode && it.localRoomTurn == group && it.replyToId == reply?.messageId.orEmpty() && it.requestMessageId == requestMessageId) { "请求编号已用于其他任务" }
            return@synchronized it
        }
        val previous = parentId?.let { originalId ->
            FriendRunIds.run(originalId)
            val original = items.firstOrNull { it.id == originalId } ?: error("找不到原任务")
            require(original.state in setOf("failed", "interrupted", "cancelled", "requires_review")) { "该任务不能重试" }
            require(original.resourceId == resourceId && original.team == team && original.input == input && original.conversationMode == conversationMode && original.localRoomTurn == group)
            ensureNotFenced(original, items)
            if (original.contextId.isNotBlank()) {
                if (original.state == "requires_review" || friendCheckpointNeedsReview(original.checkpoint))
                    fail("CHECKPOINT_REVIEW", "请先核查子代理的工具结果，不能通过重新运行绕过核查。")
                require(latestContext(original.contextId, items).id == original.id) { "该上下文已有更新的任务，请使用最新任务" }
                require(items.none { it.contextId == original.contextId && it.active }) { "该子代理仍在运行" }
                ensureNotExpired(original, items, clock())
            }
            original
        }
        val now = clock()
        val isolatedRoot = parentId == null && !team && group == null && historySnapshot != null
        val rootContextId = if (isolatedRoot) FriendRunIds.newId() else ""
        val initialMessages = when {
            isolatedRoot -> mapOf(resourceId to historySnapshot!!.toList())
            previous != null -> previous.agentMessages
            else -> emptyMap()
        }
        if (isolatedRoot) SubagentPolicy.validateContext(resourceId, initialMessages, emptySet())
        FriendRun(FriendRunIds.newId(), resourceId, input, team, clientRequestId, created = now, parentId = parentId, conversationMode = conversationMode, localRoomTurn = group,
            replyToId = reply?.messageId.orEmpty(), replyToRole = reply?.role.orEmpty(), replyPreview = reply?.preview.orEmpty(),
            requestMessageId = requestMessageId, rootContextId = rootContextId,
            contextId = previous?.contextId.orEmpty(), agentParentId = previous?.agentParentId, agentDepth = previous?.agentDepth ?: 0,
            agentTitle = previous?.agentTitle.orEmpty(),
            maxModelCalls = if (previous != null && previous.contextId.isNotBlank()) previous.maxModelCalls else SubagentPolicy.ROOT_MAX_MODEL_CALLS,
            tokenBudget = if (previous != null && previous.contextId.isNotBlank()) previous.tokenBudget else SubagentPolicy.ROOT_TOKEN_BUDGET,
            deadlineAt = previous?.deadlineAt ?: 0, agentMessages = initialMessages, agentHistorySteps = previous?.agentHistorySteps.orEmpty(),
            agentTimeoutMillis = previous?.agentTimeoutMillis ?: 0).also { storage.write(items + it) }
    }

    /** Durable delegation; retries of the same tool call return its accepted task. */
    fun spawnChild(parentRunId: String, resourceId: String, input: String, clientRequestId: String,
        title: String = "", maxModelCalls: Int = 8, tokenBudget: Long = 32_000, timeoutMillis: Long = 180_000): FriendRun = synchronized(lock) {
        FriendRunIds.run(parentRunId); FriendRunIds.resource(resourceId); FriendRunIds.request(clientRequestId)
        require(input.isNotBlank() && input.length <= 12000) { "子任务内容须为 1–12000 个字符" }
        require(title.length <= 80 && maxModelCalls in 1..SubagentPolicy.MAX_TASK_MODEL_CALLS && tokenBudget in 1..SubagentPolicy.MAX_TASK_TOKENS)
        require(timeoutMillis in 1..SubagentPolicy.MAX_CHILD_TIMEOUT_MILLIS) { "子任务运行时限无效" }
        val items = storage.read()
        items.firstOrNull { it.clientRequestId == clientRequestId }?.let {
            require(it.agentParentId == parentRunId && it.resourceId == resourceId && it.input == input && it.agentTitle == title &&
                it.maxModelCalls == maxModelCalls && it.tokenBudget == tokenBudget && it.agentTimeoutMillis == timeoutMillis) { "请求编号已用于其他任务" }
            return@synchronized it
        }
        val parent = items.firstOrNull { it.id == parentRunId } ?: error("找不到父任务")
        ensureNotFenced(parent, items)
        if (parent.state != "running") fail("AGENT_PARENT", "父任务已停止，不能继续创建子代理。")
        if (parent.agentDepth >= SubagentPolicy.MAX_DEPTH) fail("AGENT_DEPTH", "已达到子代理的最大层级。")
        val family = treeMembers(parent, items)
        if (family.filter { it.agentParentId != null }.map { it.contextId }.distinct().size >= SubagentPolicy.MAX_CHILDREN)
            fail("AGENT_BUDGET", "已达到当前任务可创建的子代理数量上限。")
        if (parent.agentDepth > 0) checkBudget(parent, items, 0)
        if (family.sumOf { it.modelCallsUsed.toLong() } >= SubagentPolicy.MAX_TREE_MODEL_CALLS ||
            family.sumOf { it.tokensReserved } >= SubagentPolicy.MAX_TREE_TOKENS)
            fail("AGENT_BUDGET", "当前任务及全部子代理的总预算已用尽，不能创建更多子代理。")
        if (parent.agentDepth > 0 && (maxModelCalls > parent.maxModelCalls || tokenBudget > parent.tokenBudget))
            fail("AGENT_BUDGET", "子代理预算不能超过父代理预算。")
        val now = clock()
        ensureNotExpired(parent, items, now)
        val root = treeRoot(parent, items)
        val rootDeadline = root.deadlineAt.takeIf { it > 0 } ?: (now + SubagentPolicy.ROOT_TIMEOUT_MILLIS)
        val parentDeadline = parent.deadlineAt.takeIf { it > 0 }?.coerceAtMost(rootDeadline) ?: rootDeadline
        val child = FriendRun(FriendRunIds.newId(), resourceId, input, false, clientRequestId, created = now,
            conversationMode = parent.conversationMode, contextId = FriendRunIds.newId(), agentParentId = parent.id,
            agentDepth = parent.agentDepth + 1, agentTitle = title, maxModelCalls = maxModelCalls, tokenBudget = tokenBudget,
            deadlineAt = minOf(parentDeadline, now + timeoutMillis), agentTimeoutMillis = timeoutMillis)
        storage.write(items.map {
            when {
                it.id == root.id && it.deadlineAt == 0L -> it.copy(deadlineAt = rootDeadline)
                it.id == parent.id && it.deadlineAt == 0L -> it.copy(deadlineAt = parentDeadline)
                else -> it
            }
        } + child)
        child
    }

    /** A user-directed next turn reuses context and limits, but receives a new checkpoint/run id. */
    fun followUpAgent(id: String, input: String, clientRequestId: String): FriendRun = synchronized(lock) {
        FriendRunIds.run(id); FriendRunIds.request(clientRequestId)
        require(input.isNotBlank() && input.length <= 12000) { "子任务内容须为 1–12000 个字符" }
        val items = storage.read()
        val requested = items.firstOrNull { it.id == id } ?: error("找不到子代理")
        require(requested.contextId.isNotBlank() && requested.agentParentId != null) { "该任务不是子代理" }
        items.firstOrNull { it.clientRequestId == clientRequestId }?.let {
            require(it.contextId == requested.contextId && it.input == input && it.resourceId == requested.resourceId) { "请求编号已用于其他任务" }
            return@synchronized it
        }
        val current = latestContext(requested.contextId, items)
        ensureNotFenced(current, items)
        if (current.state !in setOf("completed", "failed", "interrupted") || friendCheckpointNeedsReview(current.checkpoint) || items.any { it.contextId == current.contextId && it.active })
            fail("AGENT_BUSY", "子代理仍在运行或需要核查，请处理当前任务后再发送。")
        ensureNotExpired(current, items, clock())
        checkBudget(current, items, 1)
        val now = clock()
        val next = current.copy(id = FriendRunIds.newId(), input = input, clientRequestId = clientRequestId,
            state = "queued", partial = "", errorCode = "", diagnosticId = "", errorMessage = "等待子代理处理补充消息",
            created = now, updated = now, parentId = current.id, checkpoint = null, verifyRequested = false,
            modelCallsUsed = 0, tokensReserved = 0, modelReservations = emptyMap(), replyToId = "", replyToRole = "", replyPreview = "")
        storage.write(items + next)
        next
    }

    /** Every actual network attempt needs its own reservation id; cached responses need none. */
    fun admitModelRequest(id: String, requestId: String, reservedTokens: Long): FriendRun = synchronized(lock) {
        FriendRunIds.run(id); FriendRunIds.request(requestId)
        require(reservedTokens in 1..SubagentPolicy.MAX_TREE_TOKENS) { "模型预算预留无效" }
        val items = storage.read()
        val current = items.firstOrNull { it.id == id } ?: error("找不到任务")
        ensureNotFenced(current, items)
        if (current.state != "running") fail("AGENT_CANCELLED", "任务已停止，不能继续请求模型。")
        val now = clock()
        ensureNotExpired(current, items, now)
        current.modelReservations[requestId]?.let {
            require(it == reservedTokens) { "请求编号的预算已变化" }
            return@synchronized current
        }
        checkBudget(current, items, reservedTokens)
        val root = treeRoot(current, items)
        val deadline = root.deadlineAt.takeIf { it > 0 } ?: (now + SubagentPolicy.ROOT_TIMEOUT_MILLIS)
        val updated = current.copy(modelCallsUsed = current.modelCallsUsed + 1,
            tokensReserved = current.tokensReserved + reservedTokens,
            modelReservations = current.modelReservations + (requestId to reservedTokens),
            deadlineAt = current.deadlineAt.takeIf { it > 0 }?.coerceAtMost(deadline) ?: deadline, updated = now)
        storage.write(items.map {
            when {
                it.id == current.id -> updated
                it.id == root.id && it.deadlineAt == 0L -> it.copy(deadlineAt = deadline)
                else -> it
            }
        })
        updated
    }

    fun saveAgentContext(id: String, messages: Map<String, List<Message>>, historySteps: Set<String>): FriendRun = synchronized(lock) {
        FriendRunIds.run(id)
        val items = storage.read()
        val current = items.firstOrNull { it.id == id } ?: error("找不到子代理")
        ensureNotFenced(current, items)
        if (current.state != "running") fail("AGENT_CANCELLED", "子代理已停止，不能保存新的上下文。")
        require(current.contextId.isNotBlank() && latestContext(current.contextId, items).id == current.id) { "子代理上下文已更新" }
        SubagentPolicy.validateContext(current.resourceId, messages, historySteps)
        val updated = current.copy(agentMessages = messages.mapValues { it.value.toList() },
            agentHistorySteps = historySteps.toSet(), updated = clock())
        storage.write(items.map { if (it.id == id) updated else it })
        updated
    }

    fun saveRootContext(id: String, messages: Map<String, List<Message>>, historySteps: Set<String>): FriendRun = synchronized(lock) {
        FriendRunIds.run(id)
        val items = storage.read()
        val current = items.firstOrNull { it.id == id } ?: error("找不到根任务")
        ensureNotFenced(current, items)
        if (current.state != "running") fail("AGENT_CANCELLED", "任务已停止，不能保存新的上下文。")
        require(current.agentParentId == null && current.contextId.isBlank() && current.rootContextId.isNotBlank()) { "该任务没有独立根上下文" }
        SubagentPolicy.validateContext(current.resourceId, messages, historySteps)
        val updated = current.copy(agentMessages = messages.mapValues { it.value.toList() },
            agentHistorySteps = historySteps.toSet(), updated = clock())
        storage.write(items.map { if (it.id == id) updated else it })
        updated
    }

    /** Only a still-queued task may fail startup; never overwrite an already claimed worker. */
    fun startFailed(id: String) = update(id) {
        if (it.state == "queued") it.copy(state = "interrupted", errorCode = "service_start_failed",
            errorMessage = "后台任务未能启动，输入与进度已保留，请重试。", diagnosticId = FriendRunIds.newId()) else it
    }

    fun claim(): FriendRun? = synchronized(lock) {
        val items = storage.read()
        if (items.any { it.state == "running" }) return@synchronized null
        val next = items.firstOrNull { it.state == "queued" } ?: return@synchronized null
        val claimed = next.copy(state = "running", errorMessage = "正在运行", updated = clock())
        storage.write(items.map { if (it.id == claimed.id) claimed else it }); claimed
    }

    fun claimConcurrent(): FriendRun? = synchronized(lock) {
        val before = storage.read()
        val now = clock()
        val items = before.map { run ->
            if (run.state != "queued") run
            else if (cancellationFenced(run, before)) cancelled(run, "parent_cancelled", true, now)
            else if (expired(run, before, now)) expiredRun(run, now)
            else run
        }
        val running = items.filter { it.state == "running" }
        if (running.size >= SubagentPolicy.MAX_CONCURRENT) {
            if (items != before) storage.write(items)
            return@synchronized null
        }
        val next = items.firstOrNull { candidate -> candidate.state == "queued" && running.none { busy -> conflicts(candidate, busy) } }
        val claimed = next?.copy(state = "running", errorMessage = "正在运行", updated = now)
        val after = if (claimed == null) items else items.map { if (it.id == claimed.id) claimed else it }
        if (after != before) storage.write(after)
        claimed
    }

    private fun conflicts(candidate: FriendRun, busy: FriendRun): Boolean {
        // Subagents are isolated by contextId, and only one attempt per context may run.
        if (candidate.contextId.isNotBlank() || busy.contextId.isNotBlank())
            return candidate.contextId == busy.contextId
        // Teams/groups still share member workspaces and public transcripts; keep them serial
        // until member-level snapshots exist.
        if (candidate.team || busy.team || candidate.localRoomTurn != null || busy.localRoomTurn != null)
            return true
        // Ordinary roots with their own submission snapshot can overlap even for the same expert.
        // They share the workspace, which is protected by per-file write locks, not by the queue.
        if (candidate.rootContextId.isNotBlank() && busy.rootContextId.isNotBlank()) return false
        return candidate.resourceId == busy.resourceId
    }

    fun cancel(id: String) = synchronized(lock) {
        FriendRunIds.run(id)
        val items = storage.read()
        val target = items.firstOrNull { it.id == id } ?: return@synchronized
        val affected = descendants(attemptMembers(target, items).map { it.id }.toSet(), items)
        val graph = affected.any { candidate -> items.any { it.id == candidate && (it.agentParentId != null || it.contextId.isNotBlank()) } }
        val now = clock()
        val after = items.map { run ->
            if (run.id !in affected) run
            else if (run.active) cancelled(run, if (run.id == id) "cancelled" else "parent_cancelled", graph, now)
            else if (graph && !run.agentCancelled) run.copy(agentCancelled = true, updated = now)
            else run
        }
        if (after != items) storage.write(after)
    }

    fun cancelAll() = synchronized(lock) {
        val items = storage.read()
        val graphIds = items.filter { it.agentParentId != null || it.contextId.isNotBlank() }
            .flatMap { ancestors(it, items).map(FriendRun::id) + it.id }.toSet()
        val now = clock()
        val after = items.map { run ->
            if (run.active) cancelled(run, "authorization_ended", run.id in graphIds, now)
            else if (run.id in graphIds && !run.agentCancelled) run.copy(agentCancelled = true, updated = now)
            else run
        }
        if (after != items) storage.write(after)
    }

    fun checkpoint(id: String, partial: String): Boolean = update(id) {
        if (it.state == "running" && !it.agentCancelled) it.copy(partial = partial) else it
    }?.let { it.state == "running" && !it.agentCancelled } == true

    fun saveCheckpoint(id: String, checkpoint: String): Boolean = update(id) {
        if (it.state == "running" && !it.agentCancelled) it.copy(checkpoint = checkpoint,
            verifyRequested = it.verifyRequested && friendCheckpointNeedsReview(checkpoint)) else it
    }?.let { it.state == "running" && !it.agentCancelled && it.checkpoint == checkpoint } == true

    fun resume(id: String): FriendRun = synchronized(lock) {
        FriendRunIds.run(id)
        val items = storage.read()
        val current = items.firstOrNull { it.id == id } ?: error("找不到任务")
        ensureNotFenced(current, items); ensureNotExpired(current, items, clock())
        if (current.contextId.isNotBlank()) require(latestContext(current.contextId, items).id == current.id &&
            items.none { it.id != id && it.contextId == current.contextId && it.active }) { "该子代理上下文已有其他任务" }
        if (current.state == "queued" && current.checkpoint != null && !friendCheckpointNeedsReview(current.checkpoint)) return@synchronized current
        require(current.canResume) { "没有可安全继续的检查点，请重新运行或核查工具结果。" }
        update(id) { it.copy(state = "queued", errorCode = "", errorMessage = "等待从检查点继续", diagnosticId = "") }!!
    }

    fun verifyAndResume(id: String): FriendRun = synchronized(lock) {
        FriendRunIds.run(id)
        val items = storage.read()
        val current = items.firstOrNull { it.id == id } ?: error("找不到任务")
        ensureNotFenced(current, items); ensureNotExpired(current, items, clock())
        if (current.contextId.isNotBlank()) require(latestContext(current.contextId, items).id == current.id &&
            items.none { it.id != id && it.contextId == current.contextId && it.active }) { "该子代理上下文已有其他任务" }
        require(current.canVerifyCheckpoint) { "此检查点无法安全核查，请检查后明确重新发起。" }
        update(id) { it.copy(state = "queued", verifyRequested = true, errorCode = "", diagnosticId = "",
            errorMessage = "等待核查已执行操作；无法确认时不会重放。") }!!
    }

    fun finish(id: String, partial: String, state: String, errorCode: String = "", errorMessage: String,
        diagnosticId: String = if (errorCode.isBlank()) "" else FriendRunIds.newId()): Boolean {
        require(state in setOf("completed", "failed", "interrupted", "requires_review"))
        return update(id) {
            // Cancellation wins over a late model response. Preserve useful output even after cancel.
            if (it.state == "running" && !it.agentCancelled) it.copy(partial = partial, state = state, errorCode = errorCode, errorMessage = errorMessage, diagnosticId = diagnosticId)
            else if (it.state == "cancelled" || it.state == "requires_review") it.copy(partial = partial) else it
        }?.let { it.state == state && !it.agentCancelled } == true
    }

    private fun update(id: String, transform: (FriendRun) -> FriendRun): FriendRun? = synchronized(lock) {
        val items = storage.read(); val original = items.firstOrNull { it.id == id } ?: return@synchronized null
        val updated = transform(original)
        if (updated != original) storage.write(items.map { if (it.id == id) updated.copy(updated = clock()) else it })
        updated
    }

    private fun fail(code: String, message: String): Nothing = throw FriendModelFailure(code, message)
    private fun ensureNotFenced(run: FriendRun, items: List<FriendRun>) {
        if (cancellationFenced(run, items)) fail("AGENT_CANCELLED", "父任务或子代理已取消，不能继续执行。")
    }
    private fun cancellationFenced(run: FriendRun, items: List<FriendRun>): Boolean =
        run.agentCancelled || ancestors(run, items).any { it.agentCancelled } ||
            agentAncestors(run, items).any { it.state == "cancelled" }

    private fun ensureNotExpired(run: FriendRun, items: List<FriendRun>, now: Long) {
        if (expired(run, items, now)) fail("AGENT_DEADLINE", "任务运行时限已到，已保留上下文和检查点。")
    }
    private fun expired(run: FriendRun, items: List<FriendRun>, now: Long): Boolean =
        (listOf(run) + ancestors(run, items)).any { it.deadlineAt > 0 && now >= it.deadlineAt }

    private fun expiredRun(run: FriendRun, now: Long): FriendRun {
        val review = friendCheckpointNeedsReview(run.checkpoint)
        return run.copy(state = if (review) "requires_review" else "failed", errorCode = "AGENT_DEADLINE",
            errorMessage = if (review) "任务时限已到；工具结果需要核查，不能自动重放。" else "任务运行时限已到，已保留上下文和检查点。",
            diagnosticId = FriendRunIds.newId(), updated = now)
    }
    private fun cancelled(run: FriendRun, code: String, fence: Boolean, now: Long): FriendRun {
        val review = friendCheckpointNeedsReview(run.checkpoint)
        return run.copy(state = if (review) "requires_review" else "cancelled", agentCancelled = run.agentCancelled || fence,
            errorCode = if (review) "tool_effect_uncertain" else code,
            errorMessage = if (review) "任务已停止；工具操作结果不确定，请核查，不能自动重放。" else if (code == "authorization_ended") "账号授权已结束，任务已取消" else if (code == "parent_cancelled") "父任务已取消，子代理已停止" else "已取消",
            updated = now)
    }

    private fun checkBudget(run: FriendRun, items: List<FriendRun>, reservedTokens: Long) {
        // A root retry is a new task with a fresh budget; only a delegated child keeps its
        // context's shared attempt budget.
        val local = if (run.agentDepth > 0) subtreeMembers(run, items) else currentAttemptTree(run, items)
        if (local.sumOf { it.modelCallsUsed.toLong() } >= run.maxModelCalls || local.sumOf { it.tokensReserved } + reservedTokens > run.tokenBudget)
            fail("AGENT_BUDGET", "当前代理的模型请求或预算已用尽，进度已保留。")
        for (parent in agentAncestors(run, items).filter { it.agentDepth > 0 }) {
            val family = subtreeMembers(parent, items)
            if (family.sumOf { it.modelCallsUsed.toLong() } >= parent.maxModelCalls || family.sumOf { it.tokensReserved } + reservedTokens > parent.tokenBudget)
                fail("AGENT_BUDGET", "父代理的共享预算已用尽，进度已保留。")
        }
        // A delegated child must also respect the ordinary root task's budget, not only the
        // global tree ceiling. The root itself is checked by the local branch above.
        val root = agentRoot(run, items)
        if (root.id != run.id) {
            val family = currentAttemptTree(root, items)
            if (family.sumOf { it.modelCallsUsed.toLong() } >= root.maxModelCalls || family.sumOf { it.tokensReserved } + reservedTokens > root.tokenBudget)
                fail("AGENT_BUDGET", "根任务的模型请求或预算已用尽，进度已保留。")
        }
        val tree = currentAttemptTree(root, items)
        if (tree.sumOf { it.modelCallsUsed.toLong() } >= SubagentPolicy.MAX_TREE_MODEL_CALLS || tree.sumOf { it.tokensReserved } + reservedTokens > SubagentPolicy.MAX_TREE_TOKENS)
            fail("AGENT_BUDGET", "当前任务及全部子代理的总预算已用尽，进度已保留。")
    }

    private fun latestContext(contextId: String, items: List<FriendRun>): FriendRun = items.withIndex()
        .filter { it.value.contextId == contextId }.maxWithOrNull(compareBy<IndexedValue<FriendRun>> { it.value.updated }.thenBy { it.index })!!.value

    private fun attemptRoot(run: FriendRun, items: List<FriendRun>): FriendRun {
        var current = run; val seen = mutableSetOf<String>()
        while (current.parentId != null) {
            require(seen.add(current.id)) { "任务重试关系存在循环" }
            current = items.firstOrNull { it.id == current.parentId } ?: break
        }
        return current
    }
    private fun attemptMembers(run: FriendRun, items: List<FriendRun>): List<FriendRun> {
        val root = attemptRoot(run, items).id
        return items.filter { attemptRoot(it, items).id == root }
    }
    private fun treeRoot(run: FriendRun, items: List<FriendRun>): FriendRun {
        var current = run; val seen = mutableSetOf<String>()
        while (true) {
            require(seen.add(current.id)) { "子代理父任务关系存在循环" }
            val parent = current.agentParentId ?: current.parentId ?: return current
            current = items.firstOrNull { it.id == parent } ?: if (current.agentParentId == null) return current else error("找不到子代理父任务")
        }
    }
    private fun treeMembers(run: FriendRun, items: List<FriendRun>): List<FriendRun> {
        val root = treeRoot(run, items).id
        return items.filter { treeRoot(it, items).id == root }
    }
    private fun subtreeMembers(run: FriendRun, items: List<FriendRun>): List<FriendRun> {
        val ids = descendants(attemptMembers(run, items).map { it.id }.toSet(), items)
        return items.filter { it.id in ids }
    }
    /** Descendants of this exact root attempt, excluding older retry attempts. */
    private fun currentAttemptTree(run: FriendRun, items: List<FriendRun>): List<FriendRun> {
        val ids = descendants(setOf(run.id), items)
        return items.filter { it.id in ids }
    }
    /** The agent-graph root; ignores parentId retry links so a retry gets its own budget. */
    private fun agentRoot(run: FriendRun, items: List<FriendRun>): FriendRun {
        var current = run
        val seen = mutableSetOf<String>()
        while (true) {
            require(seen.add(current.id)) { "子代理父任务关系存在循环" }
            val parent = current.agentParentId ?: return current
            current = items.firstOrNull { it.id == parent } ?: error("找不到子代理父任务")
        }
    }
    private fun descendants(initial: Set<String>, items: List<FriendRun>): Set<String> {
        val found = initial.toMutableSet()
        do {
            val added = items.filter { it.id !in found && (it.agentParentId in found || it.parentId in found) }.map { it.id }
            found.addAll(added)
        } while (added.isNotEmpty())
        return found
    }
    private fun ancestors(run: FriendRun, items: List<FriendRun>): List<FriendRun> {
        val pending = mutableListOf(run); val result = linkedMapOf<String, FriendRun>()
        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.lastIndex)
            for (id in listOfNotNull(current.agentParentId, current.parentId)) {
                val parent = items.firstOrNull { it.id == id } ?: continue
                require(parent.id != run.id) { "子代理父任务关系存在循环" }
                if (result.putIfAbsent(parent.id, parent) == null) pending += parent
            }
        }
        return result.values.toList()
    }
    private fun agentAncestors(run: FriendRun, items: List<FriendRun>): List<FriendRun> {
        val result = mutableListOf<FriendRun>(); var current = run
        while (current.agentParentId != null) {
            val parent = items.firstOrNull { it.id == current.agentParentId } ?: error("找不到子代理父任务")
            require(result.none { it.id == parent.id } && parent.id != run.id) { "子代理父任务关系存在循环" }
            result += parent; current = parent
        }
        return result
    }
}
