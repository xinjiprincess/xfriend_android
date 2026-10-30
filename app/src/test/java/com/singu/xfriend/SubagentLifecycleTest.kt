package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentLifecycleTest {
    private class Storage : FriendRunStorage {
        private var encoded = FriendRunCodec.encode(emptyList())
        override fun read(): List<FriendRun> = FriendRunCodec.decode(encoded)
        override fun write(runs: List<FriendRun>) { encoded = FriendRunCodec.encode(runs) }
    }
    private fun newQueue() = FriendRunQueue(Storage(), Any())
    private fun newScope() = FriendRunIds.newId().replace("-", "").repeat(2)
    private fun running(queue: FriendRunQueue): FriendRun {
        queue.enqueue("xfriend", "父任务", false, "root")
        return queue.claimConcurrent()!!
    }

    @Test fun lazyJobCancelledBeforeBodyEntryStillReleasesItsSlotAndPersistsInterruption() = runBlocking {
        val queue = newQueue(); val scope = newScope(); val run = running(queue)
        var entered = false; var signals = 0
        val worker = launch(start = CoroutineStart.LAZY) { entered = true }
        try {
            assertTrue(FriendRunLiveness.reserveRun(scope, run.id, worker))
            worker.invokeOnCompletion { completeFriendRun(queue, scope, run.id, worker) { signals++ } }
            worker.cancel(); worker.join()

            assertFalse(entered)
            assertEquals(1, signals)
            assertFalse(FriendRunLiveness.active(scope))
            assertNull(FriendRunLiveness.job(scope, run.id))
            assertEquals("interrupted", queue.runs().single().state)
            assertEquals("execution_interrupted", queue.runs().single().errorCode)
            assertTrue(friendRunSchedulerIdle(mapOf(scope to queue)))
        } finally { FriendRunLiveness.removeRun(scope, run.id, worker); worker.cancel() }
    }

    @Test fun completionRetainsUncertainEffectsAsReviewAndKeepsCancelledResultsCancelled() {
        val queue = newQueue(); val scope = newScope(); val run = running(queue); val worker = Job()
        val checkpoint = JSONObject().put("version", 1).put("phase", "pending_effect").toString()
        queue.saveCheckpoint(run.id, checkpoint); queue.checkpoint(run.id, "已产生的部分结果")
        try {
            assertTrue(FriendRunLiveness.reserveRun(scope, run.id, worker))
            worker.complete()
            completeFriendRun(queue, scope, run.id, worker) {}
            val saved = queue.runs().single()
            assertEquals("requires_review", saved.state)
            assertEquals(checkpoint, saved.checkpoint)
            assertEquals("已产生的部分结果", saved.partial)
            assertFalse(FriendRunLiveness.active(scope))
        } finally { FriendRunLiveness.removeRun(scope, run.id, worker); worker.cancel() }

        val otherQueue = newQueue(); val otherScope = newScope(); val cancelled = running(otherQueue); val otherWorker = Job()
        otherQueue.checkpoint(cancelled.id, "停止前已保存")
        otherQueue.cancel(cancelled.id)
        try {
            assertTrue(FriendRunLiveness.reserveRun(otherScope, cancelled.id, otherWorker))
            assertFalse(otherQueue.finish(cancelled.id, "晚到的模型结果", "completed", errorMessage = "完成"))
            otherWorker.complete()
            completeFriendRun(otherQueue, otherScope, cancelled.id, otherWorker) {}
            assertEquals("cancelled", otherQueue.runs().single().state)
            assertEquals("晚到的模型结果", otherQueue.runs().single().partial)
            assertFalse(FriendRunLiveness.active(otherScope))
        } finally { FriendRunLiveness.removeRun(otherScope, cancelled.id, otherWorker); otherWorker.cancel() }
    }

    @Test fun delayedOldCompletionCannotFinishOrRemoveAReplacementWorker() {
        val queue = newQueue(); val scope = newScope(); val run = running(queue)
        val old = Job(); val replacement = Job(); var signals = 0
        try {
            assertTrue(FriendRunLiveness.reserveRun(scope, run.id, old))
            FriendRunLiveness.removeRun(scope, run.id, old)
            assertTrue(FriendRunLiveness.reserveRun(scope, run.id, replacement))
            old.complete()
            completeFriendRun(queue, scope, run.id, old) { signals++ }

            assertEquals(1, signals)
            assertEquals("running", queue.runs().single().state)
            assertSame(replacement, FriendRunLiveness.job(scope, run.id))
            assertFalse(friendRunSchedulerIdle(mapOf(scope to queue)))
        } finally {
            FriendRunLiveness.removeRun(scope, run.id, old)
            FriendRunLiveness.removeRun(scope, run.id, replacement)
            old.cancel(); replacement.cancel()
        }
    }

    @Test fun completingTheLastCoordinatorCannotMakeTheSchedulerIdleWhileItsChildIsQueued() {
        val queue = newQueue(); val scope = newScope(); val run = running(queue); val worker = Job()
        try {
            assertTrue(FriendRunLiveness.reserveRun(scope, run.id, worker))
            val child = queue.spawnChild(run.id, "xfriend", "父任务返回前刚交办的子任务", "child")
            queue.finish(run.id, "子任务已受理", "completed", errorMessage = "完成")
            worker.complete()
            completeFriendRun(queue, scope, run.id, worker) {}

            assertFalse(FriendRunLiveness.active(scope))
            assertEquals("completed", queue.runs().first { it.id == run.id }.state)
            assertEquals("queued", queue.runs().first { it.id == child.id }.state)
            assertFalse(friendRunSchedulerIdle(mapOf(scope to queue)))
            assertEquals(child.id, queue.claimConcurrent()!!.id)
        } finally { FriendRunLiveness.removeRun(scope, run.id, worker); worker.cancel() }
    }

    @Test fun aFollowUpCanInspectAndCancelItsEarlierTurnsGrandchildButNeverItsSibling() = runBlocking {
        val queue = newQueue(); val root = running(queue)
        val child = queue.spawnChild(root.id, "xfriend", "负责分析", "child")
        val sibling = queue.spawnChild(root.id, "xfriend", "其他独立分析", "sibling")
        assertEquals(child.id, queue.claimConcurrent()!!.id)
        val grandchild = queue.spawnChild(child.id, "xfriend", "分析的分工", "grandchild")
        queue.finish(child.id, "已交给下一层", "completed", errorMessage = "完成")
        queue.finish(root.id, "可继续主会话", "completed", errorMessage = "完成")
        val followUp = queue.followUpAgent(child.id, "检查刚才分工的进度", "follow-up")
        assertEquals(sibling.id, queue.claimConcurrent()!!.id)
        assertEquals(grandchild.id, queue.claimConcurrent()!!.id)
        assertEquals(followUp.id, queue.claimConcurrent()!!.id)
        val adapter = PersistentSubagentAdapter(queue, followUp.id, { true })

        val visible = adapter.list().getJSONArray("tasks").objects().map { it.getString("task_id") }
        assertTrue(grandchild.id in visible)
        assertFalse(sibling.id in visible)
        assertEquals("running", adapter.result(grandchild.id).getString("state"))
        try { adapter.result(sibling.id); fail("A sibling result was exposed") } catch (_: IllegalArgumentException) { }
        try { adapter.cancel(sibling.id); fail("A sibling was cancelled") } catch (_: IllegalArgumentException) { }
        assertEquals("cancelled", adapter.cancel(grandchild.id).getString("state"))
        assertEquals("running", queue.runs().first { it.id == sibling.id }.state)
        assertFalse(queue.runs().first { it.id == sibling.id }.agentCancelled)
    }

    @Test fun anOldUnauthorizedWorkerCannotCancelANewLoginsUnrelatedQueuedRoot() = runBlocking {
        val queue = newQueue(); val old = running(queue)
        val fresh = queue.enqueue("other", "重新登录后刚提交的任务", false, "new-login-request")
        var executions = 0
        val execution = object : FriendRunExecution {
            override suspend fun preflight(resourceId: String, team: Boolean) { executions++; error("Old authorization must fail first") }
            override suspend fun run(resourceId: String, input: String, team: Boolean,
                onStatus: (String) -> Unit, onDelta: (String) -> Unit): String = error("Old worker must not execute")
        }
        try { FriendRunWorker(queue, execution, { false }).execute(old); fail("Expected expired-generation cancellation") }
        catch (_: CancellationException) { }

        assertEquals(0, executions)
        assertEquals("cancelled", queue.runs().first { it.id == old.id }.state)
        assertEquals("queued", queue.runs().first { it.id == fresh.id }.state)
        assertEquals(fresh.id, queue.claimConcurrent()!!.id)
    }
}
