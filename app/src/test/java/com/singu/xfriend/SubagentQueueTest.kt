package com.singu.xfriend

import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors

class SubagentQueueTest {
    private class Storage : FriendRunStorage {
        var encoded = FriendRunCodec.encode(emptyList())
        var failWrite = false
        override fun read(): List<FriendRun> = FriendRunCodec.decode(encoded)
        override fun write(runs: List<FriendRun>) {
            if (failWrite) throw IOException("disk full")
            encoded = FriendRunCodec.encode(runs)
        }
    }
    private class Clock(var now: Long = 1_000) { fun tick() = now }
    private fun root(queue: FriendRunQueue): FriendRun {
        queue.enqueue("main", "用户的总任务", false, "root", conversationMode = ConversationMode.CRAFT)
        return queue.claimConcurrent()!!
    }
    private fun ready() = JSONObject().put("version", 1).put("phase", "ready").put("turn", 1).toString()
    private fun pending() = JSONObject().put("version", 1).put("phase", "pending_effect").toString()
    private fun assertCode(code: String, action: () -> Unit) {
        assertEquals(code, assertThrows(FriendModelFailure::class.java, action).code)
    }

    @Test fun oldQueueRowsWithoutAgentFieldsStillLoadWithoutNewRuntimeRestrictions() {
        val old = FriendRun(FriendRunIds.newId(), "main", "旧任务", false, "legacy")
        val json = JSONObject(FriendRunCodec.encode(listOf(old)))
        val row = json.getJSONArray("runs").getJSONObject(0)
        listOf("contextId", "agentParentId", "agentDepth", "agentTitle", "maxModelCalls", "modelCallsUsed", "tokenBudget",
            "tokensReserved", "deadlineAt", "agentMessages", "agentHistorySteps", "modelReservations", "agentCancelled", "agentTimeoutMillis").forEach(row::remove)
        assertEquals(old, FriendRunCodec.decode(json.toString()).single())
        assertEquals(0L, FriendRunCodec.decode(json.toString()).single().deadlineAt)
    }

    @Test fun spawnIsDurableIdempotentAndOnlyPassesTheExplicitInputIntoAnIndependentContext() {
        val memory = Storage(); val clock = Clock(); val q = FriendRunQueue(memory, Any(), clock::tick)
        val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "仅分析这个问题", "tool-1", "分析", maxModelCalls = 4, tokenBudget = 12_000)
        assertEquals(parent.id, child.agentParentId)
        assertNull(child.parentId)
        assertEquals(1, child.agentDepth)
        FriendRunIds.run(child.contextId)
        assertEquals(ConversationMode.CRAFT, child.conversationMode)
        assertTrue(child.agentMessages.isEmpty())
        assertEquals("仅分析这个问题", child.input)
        assertEquals(child, q.spawnChild(parent.id, "worker", child.input, "tool-1", "分析", 4, 12_000))
        q.finish(parent.id, "主会话已受理", "completed", errorMessage = "完成")
        assertEquals(child.id, q.spawnChild(parent.id, "worker", child.input, "tool-1", "分析", 4, 12_000).id)
        assertThrows(IllegalArgumentException::class.java) { q.spawnChild(parent.id, "worker", "修改输入", "tool-1", "分析", 4, 12_000) }
        assertThrows(IllegalArgumentException::class.java) { q.spawnChild(parent.id, "worker", child.input, "tool-1", "分析", 4, 12_000, 50) }
        assertCode("AGENT_PARENT") { q.spawnChild(parent.id, "worker", "另一个", "tool-2") }
        assertEquals(2, memory.read().size)
    }

    @Test fun depthAndTreeSizeLimitsFailWithoutMutatingAcceptedTasks() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "一层", "child")
        assertEquals(child.id, q.claimConcurrent()!!.id)
        val grandchild = q.spawnChild(child.id, "worker", "二层", "grandchild")
        assertEquals(grandchild.id, q.claimConcurrent()!!.id)
        val before = q.runs()
        assertCode("AGENT_DEPTH") { q.spawnChild(grandchild.id, "worker", "三层", "too-deep") }
        assertEquals(before, q.runs())
        repeat(6) { q.spawnChild(parent.id, "worker", "补充分支$it", "sibling-$it") }
        assertCode("AGENT_BUDGET") { q.spawnChild(parent.id, "worker", "超出数量", "overflow") }
        assertEquals(9, q.runs().size)
    }

    @Test fun concurrentClaimsHaveThreeSlotsAndDoNotSerializeDifferentIsolatedContexts() {
        val memory = Storage(); val lock = Any(); val q = FriendRunQueue(memory, lock); val parent = root(q)
        val children = (1..3).map { q.spawnChild(parent.id, "same-expert", "分支$it", "child-$it") }
        q.enqueue("main", "同会话后续输入", false, "same-conversation")
        q.enqueue("unrelated", "另一个会话", false, "other")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val claims = (1..8).map { pool.submit<FriendRun?> { FriendRunQueue(memory, lock).claimConcurrent() } }.mapNotNull { it.get() }
            assertEquals(2, claims.size)
            assertEquals(children.take(2).map { it.id }.toSet(), claims.map { it.id }.toSet())
            assertEquals(3, q.runs().count { it.state == "running" })
            q.finish(claims.first().id, "完成", "completed", errorMessage = "完成")
            assertEquals(children.last().id, q.claimConcurrent()!!.id)
            q.finish(claims.last().id, "完成", "completed", errorMessage = "完成")
            assertEquals("unrelated", q.claimConcurrent()!!.resourceId)
            assertEquals("queued", q.runs().first { it.clientRequestId == "same-conversation" }.state)
        } finally { pool.shutdownNow() }
    }

    @Test fun teamAndGroupRootWritersWaitForOtherRootsButNotIsolatedAgents() {
        val q = FriendRunQueue(Storage(), Any())
        q.enqueue("team", "团队任务", true, "team-root")
        val team = q.claimConcurrent()!!
        val child = q.spawnChild(team.id, "worker", "隔离子代理", "child")
        q.enqueue("unrelated", "成员可能共享文件", false, "expert-root")
        assertEquals(child.id, q.claimConcurrent()!!.id)
        assertNull(q.claimConcurrent())
        q.finish(team.id, "团队已完成", "completed", errorMessage = "完成")
        assertEquals("unrelated", q.claimConcurrent()!!.resourceId)
        q.enqueue("room", "群任务", false, "group-root", localRoomTurn = LocalRoomTurn("room", listOf("worker")))
        assertNull(q.claimConcurrent())
    }

    @Test fun duplicateModelReservationDoesNotChargeTwiceAndRetryCannotResetTheTaskBudget() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "分析", "child", maxModelCalls = 2, tokenBudget = 500)
        q.claimConcurrent()
        val first = q.admitModelRequest(child.id, "network-1", 100)
        assertEquals(first, q.admitModelRequest(child.id, "network-1", 100))
        assertThrows(IllegalArgumentException::class.java) { q.admitModelRequest(child.id, "network-1", 101) }
        q.admitModelRequest(child.id, "network-2", 100)
        assertCode("AGENT_BUDGET") { q.admitModelRequest(child.id, "network-3", 1) }
        q.recover()
        val retry = q.enqueue(child.resourceId, child.input, false, "retry", child.id, child.conversationMode)
        assertEquals(child.contextId, retry.contextId)
        assertEquals(child.deadlineAt, retry.deadlineAt)
        assertEquals(retry.id, q.claimConcurrent()!!.id)
        assertCode("AGENT_BUDGET") { q.admitModelRequest(retry.id, "new-attempt", 1) }
        assertEquals(2, q.runs().sumOf { it.modelCallsUsed })
    }

    @Test fun allBranchesAndAttemptsShareTheOriginalRootBudget() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        q.admitModelRequest(parent.id, "root-network", 448_000)
        val a = q.spawnChild(parent.id, "worker", "A", "a")
        val b = q.spawnChild(parent.id, "worker", "B", "b")
        val c = q.spawnChild(parent.id, "worker", "C", "c")
        assertEquals(a.id, q.claimConcurrent()!!.id)
        q.admitModelRequest(a.id, "a-network", 32_000)
        q.finish(a.id, "A完成", "completed", errorMessage = "完成")
        assertEquals(b.id, q.claimConcurrent()!!.id)
        q.admitModelRequest(b.id, "b-network", 32_000)
        q.finish(b.id, "B完成", "completed", errorMessage = "完成")
        assertEquals(c.id, q.claimConcurrent()!!.id)
        val before = q.runs()
        assertCode("AGENT_BUDGET") { q.admitModelRequest(c.id, "c-network", 1) }
        assertEquals(before, q.runs())
        assertEquals(512_000L, q.runs().sumOf { it.tokensReserved })
    }

    @Test fun aGrandchildAlsoConsumesItsImmediateParentsSharedBudget() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "子任务", "child", maxModelCalls = 2, tokenBudget = 500)
        q.claimConcurrent(); q.admitModelRequest(child.id, "child-network", 300)
        val grandchild = q.spawnChild(child.id, "worker", "孙任务", "grandchild", maxModelCalls = 2, tokenBudget = 500)
        q.claimConcurrent(); q.admitModelRequest(grandchild.id, "grandchild-network", 200)
        assertCode("AGENT_BUDGET") { q.admitModelRequest(grandchild.id, "grandchild-network-2", 1) }
    }

    @Test fun expiredQueuedTasksBecomeDurableFailuresInsteadOfWaitingForever() {
        val clock = Clock(); val memory = Storage(); val q = FriendRunQueue(memory, Any(), clock::tick); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "分析", "child", timeoutMillis = 100)
        clock.now += 100
        assertNull(q.claimConcurrent())
        val stopped = memory.read().first { it.id == child.id }
        assertEquals("failed", stopped.state)
        assertEquals("AGENT_DEADLINE", stopped.errorCode)
        assertCode("AGENT_DEADLINE") { q.followUpAgent(child.id, "继续", "follow") }
    }

    @Test fun expirationPreservesUncertainToolEffectsForReview() {
        val clock = Clock(); val q = FriendRunQueue(Storage(), Any(), clock::tick); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "分析", "child", timeoutMillis = 100)
        q.claimConcurrent(); q.saveCheckpoint(child.id, pending())
        clock.now += 101; q.recover()
        val stopped = q.runs().first { it.id == child.id }
        assertEquals("requires_review", stopped.state)
        assertEquals(pending(), stopped.checkpoint)
        assertFalse(stopped.canResume)
    }

    @Test fun parentCancellationFencesRunningQueuedAndInterruptedDescendantsAndWinsOverLateSuccess() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "子任务", "child")
        q.claimConcurrent(); q.saveCheckpoint(child.id, pending())
        val grandchild = q.spawnChild(child.id, "worker", "孙任务", "grandchild")
        q.cancel(parent.id)
        val stoppedChild = q.runs().first { it.id == child.id }
        assertEquals("requires_review", stoppedChild.state)
        assertTrue(stoppedChild.agentCancelled)
        assertEquals("cancelled", q.runs().first { it.id == grandchild.id }.state)
        assertFalse(q.finish(child.id, "晚到的结果", "completed", errorMessage = "完成"))
        assertEquals("requires_review", q.runs().first { it.id == child.id }.state)
        assertCode("AGENT_CANCELLED") { q.resume(child.id) }
        assertCode("AGENT_CANCELLED") { q.verifyAndResume(child.id) }
        assertCode("AGENT_CANCELLED") { q.followUpAgent(child.id, "继续", "follow") }
        assertCode("AGENT_CANCELLED") { q.enqueue(child.resourceId, child.input, false, "retry", child.id, child.conversationMode) }
        assertCode("AGENT_CANCELLED") { q.saveAgentContext(child.id, emptyMap(), emptySet()) }
        assertNull(q.claimConcurrent())
    }

    @Test fun cancelOfCompletedCoordinatorStopsItsStillRunningChildrenWithoutErasingItsResult() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "分析", "child")
        q.claimConcurrent(); q.finish(parent.id, "主会话回执", "completed", errorMessage = "完成")
        q.cancel(parent.id)
        val root = q.runs().first { it.id == parent.id }
        assertEquals("completed", root.state); assertEquals("主会话回执", root.partial); assertTrue(root.agentCancelled)
        assertEquals("cancelled", q.runs().first { it.id == child.id }.state)
    }

    @Test fun followUpUsesTheLatestSameContextHistoryAndDoesNotRefreshBudgetOrDeadline() {
        val clock = Clock(); val q = FriendRunQueue(Storage(), Any(), clock::tick); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "先分析", "child", maxModelCalls = 3, tokenBudget = 5_000)
        q.claimConcurrent(); q.admitModelRequest(child.id, "network", 100)
        val messages = mapOf("worker" to listOf(Message("user", "先分析", "u1"), Message("assistant", "结论", "a1")))
        q.saveAgentContext(child.id, messages, setOf("saved-user", "saved-answer"))
        q.finish(child.id, "结论", "completed", errorMessage = "完成")
        q.finish(parent.id, "已交给子代理", "completed", errorMessage = "完成")
        clock.now += 10
        val next = q.followUpAgent(child.id, "再核查论据", "follow-up")
        assertEquals(child.contextId, next.contextId); assertEquals(child.id, next.parentId)
        assertEquals(child.agentParentId, next.agentParentId); assertEquals(child.deadlineAt, next.deadlineAt)
        assertEquals(messages, next.agentMessages); assertEquals(setOf("saved-user", "saved-answer"), next.agentHistorySteps)
        assertNull(next.checkpoint); assertEquals(0, next.modelCallsUsed)
        assertEquals(next.id, q.followUpAgent(child.id, "再核查论据", "follow-up").id)
        assertCode("AGENT_BUSY") { q.followUpAgent(child.id, "不要串行覆盖", "racing-follow-up") }
        assertEquals(next.id, q.claimConcurrent()!!.id)
        q.admitModelRequest(next.id, "next-network", 100)
        assertEquals(2, q.runs().sumOf { it.modelCallsUsed })
    }

    @Test fun cancellationOfTheNewestFollowUpAlsoStopsChildrenSpawnedByItsEarlierTurn() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "先分工", "child")
        q.claimConcurrent()
        val grandchild = q.spawnChild(child.id, "worker", "仍在处理的孙任务", "grandchild")
        q.claimConcurrent(); q.finish(child.id, "已交办", "completed", errorMessage = "完成")
        val followUp = q.followUpAgent(child.id, "补充信息", "follow-up")
        q.cancel(followUp.id)
        assertEquals("cancelled", q.runs().first { it.id == grandchild.id }.state)
        assertTrue(q.runs().first { it.id == child.id }.agentCancelled)
        assertTrue(q.runs().first { it.id == followUp.id }.agentCancelled)
        assertCode("AGENT_CANCELLED") { q.followUpAgent(child.id, "不能再复用取消上下文", "after-cancel") }
    }

    @Test fun followUpCannotBypassReviewOrExhaustedLimits() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "分析", "child", maxModelCalls = 1, tokenBudget = 100)
        q.claimConcurrent(); q.saveCheckpoint(child.id, pending())
        q.finish(child.id, "待核查", "requires_review", errorMessage = "核查")
        assertCode("AGENT_BUSY") { q.followUpAgent(child.id, "绕过核查", "follow-review") }
        assertCode("CHECKPOINT_REVIEW") { q.enqueue(child.resourceId, child.input, false, "retry-review", child.id, child.conversationMode) }
        val other = q.spawnChild(parent.id, "worker", "另一个", "other", maxModelCalls = 1, tokenBudget = 100)
        q.claimConcurrent(); q.admitModelRequest(other.id, "network", 100)
        q.finish(other.id, "完成", "completed", errorMessage = "完成")
        assertCode("AGENT_BUDGET") { q.followUpAgent(other.id, "刷新预算", "follow-exhausted") }
    }

    @Test fun savedContextsAreIsolatedAndCopiedBeforePersistence() {
        val q = FriendRunQueue(Storage(), Any()); val parent = root(q)
        val a = q.spawnChild(parent.id, "worker", "A", "a")
        val b = q.spawnChild(parent.id, "worker", "B", "b")
        q.claimConcurrent(); q.claimConcurrent()
        val rows = mutableListOf(Message("user", "A私有输入", "a-user"))
        q.saveAgentContext(a.id, mapOf("worker" to rows), setOf("a-step"))
        rows += Message("assistant", "不应凭空进入持久数据", "not-saved")
        assertEquals(1, q.runs().first { it.id == a.id }.agentMessages.getValue("worker").size)
        assertTrue(q.runs().first { it.id == b.id }.agentMessages.isEmpty())
        assertTrue(q.runs().first { it.id == parent.id }.agentMessages.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { q.saveAgentContext(a.id, mapOf("other" to rows), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { q.saveAgentContext(a.id, mapOf("worker" to List(201) { Message("user", "过多") }), emptySet()) }
    }

    @Test fun codecRecoveryAndExplicitResumeRetainGraphContextCheckpointsAndReservations() {
        val memory = Storage(); val clock = Clock(); val q = FriendRunQueue(memory, Any(), clock::tick); val parent = root(q)
        val child = q.spawnChild(parent.id, "worker", "任务", "child")
        q.claimConcurrent(); q.admitModelRequest(child.id, "actual-network", 300)
        val messages = mapOf("worker" to listOf(Message("user", "任务", "user-id"), Message("assistant", "部分结果", "answer-id")))
        q.saveAgentContext(child.id, messages, setOf("user-step", "answer-step"))
        q.saveCheckpoint(child.id, ready()); q.checkpoint(child.id, "部分结果")
        val durable = q.runs().first { it.id == child.id }
        val reopened = FriendRunQueue(memory, Any(), clock::tick)
        reopened.recover()
        val interrupted = reopened.runs().first { it.id == child.id }
        assertEquals("interrupted", interrupted.state)
        assertEquals(durable.contextId, interrupted.contextId); assertEquals(durable.agentParentId, interrupted.agentParentId)
        assertEquals(messages, interrupted.agentMessages); assertEquals(durable.agentHistorySteps, interrupted.agentHistorySteps)
        assertEquals(durable.modelReservations, interrupted.modelReservations); assertEquals(durable.deadlineAt, interrupted.deadlineAt)
        assertEquals(durable.checkpoint, interrupted.checkpoint)
        reopened.resume(child.id); assertEquals(child.id, reopened.claimConcurrent()!!.id)
        assertEquals(1, reopened.runs().first { it.id == child.id }.modelCallsUsed)
    }

    @Test fun failedDurableReservationOrSpawnDoesNotConsumeCapacityOrBudget() {
        val memory = Storage(); val q = FriendRunQueue(memory, Any()); val parent = root(q)
        val before = q.runs(); memory.failWrite = true
        assertThrows(IOException::class.java) { q.admitModelRequest(parent.id, "network", 100) }
        assertEquals(before, q.runs())
        assertThrows(IOException::class.java) { q.spawnChild(parent.id, "worker", "任务", "child") }
        assertEquals(before, q.runs())
    }

    @Test fun livenessTracksThreeJobsAndLateCleanupCannotRemoveTheirReplacement() {
        val scope = "f".repeat(64); val ids = (1..4).map { FriendRunIds.newId() }; val jobs = (1..5).map { Job() }
        try {
            repeat(3) { assertTrue(FriendRunLiveness.reserveRun(scope, ids[it], jobs[it])) }
            assertTrue(FriendRunLiveness.active(scope)); assertEquals(3, FriendRunLiveness.jobs(scope).size)
            assertFalse(FriendRunLiveness.reserveRun(scope, ids.last(), jobs[3]))
            assertFalse(FriendRunLiveness.reserveRun(scope, ids.first(), jobs[3]))
            assertFalse(FriendRunLiveness.reserve(scope, jobs[4]))
            FriendRunLiveness.removeRun(scope, ids.first(), jobs.first())
            assertTrue(FriendRunLiveness.reserveRun(scope, ids.first(), jobs[3]))
            FriendRunLiveness.removeRun(scope, ids.first(), jobs.first())
            assertEquals(jobs[3], FriendRunLiveness.job(scope, ids.first()))
        } finally { jobs.forEach { FriendRunLiveness.remove(scope, it); it.cancel() } }
        assertFalse(FriendRunLiveness.active(scope))
    }
}
