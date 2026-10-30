package com.singu.xfriend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkerDeadlineTest {
    private fun running(deadline: Long): Pair<FriendRunQueue, FriendRun> {
        val memory = FriendRunMemory()
        val queue = FriendRunQueue(memory, Any())
        queue.enqueue("xfriend", "任务", false, "request")
        val run = queue.claim()!!.copy(deadlineAt = deadline)
        memory.values = listOf(run)
        return queue to run
    }

    private fun execution(body: suspend ((String) -> Unit) -> String) = object : FriendRunExecution {
        override suspend fun preflight(resourceId: String, team: Boolean) {}
        override suspend fun run(resourceId: String, input: String, team: Boolean, onStatus: (String) -> Unit,
            onDelta: (String) -> Unit): String = body(onDelta)
    }

    @Test fun deadlineCancelsTheSuspendedRequestAndRetainsPartialOutputAsFailed() = runBlocking {
        val (queue, run) = running(System.currentTimeMillis() + 400)
        var cancelled = false
        FriendRunWorker(queue, execution { delta ->
            delta("已生成的部分")
            try { CompletableDeferred<String>().await() } finally { cancelled = true }
        }, { true }).execute(run)

        assertTrue(cancelled)
        val saved = queue.runs().single()
        assertEquals("failed", saved.state)
        assertEquals("AGENT_DEADLINE", saved.errorCode)
        assertEquals("已生成的部分", saved.partial)
        assertTrue(saved.diagnosticId.isNotBlank())
        assertNull(queue.claim())
    }

    @Test fun deadlineWithAnUncertainWriteRequiresReviewAndNeverReportsOrdinaryFailure() = runBlocking {
        val (queue, run) = running(System.currentTimeMillis() + 400)
        FriendRunWorker(queue, execution { delta ->
            delta("写入前的内容")
            queue.saveCheckpoint(run.id, JSONObject().put("version", 1).put("phase", "pending_effect").toString())
            CompletableDeferred<String>().await()
        }, { true }).execute(run)

        val saved = queue.runs().single()
        assertEquals("requires_review", saved.state)
        assertEquals("tool_effect_uncertain", saved.errorCode)
        assertEquals("写入前的内容", saved.partial)
        assertFalse(saved.canResume)
        assertNull(queue.claim())
    }

    @Test fun anAlreadyExpiredDeadlineNeverStartsExecution() = runBlocking {
        val (queue, run) = running(System.currentTimeMillis() - 1)
        var called = false
        FriendRunWorker(queue, execution { called = true; "不得开始" }, { true }).execute(run)

        assertFalse(called)
        assertEquals("failed", queue.runs().single().state)
        assertEquals("AGENT_DEADLINE", queue.runs().single().errorCode)
    }
}
