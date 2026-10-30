package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FriendRunWorkerTest {
    private fun queue() = FriendRunQueue(FriendRunMemory(), Any())
    private fun running(q: FriendRunQueue): FriendRun { q.enqueue("e", "private", false, "req"); return q.claim()!! }
    private fun execution(preflight: () -> Unit = {}, body: suspend ((String) -> Unit) -> String) = object : FriendRunExecution {
        override suspend fun preflight(resourceId: String, team: Boolean) = preflight()
        override suspend fun run(resourceId: String, input: String, team: Boolean, onStatus: (String) -> Unit, onDelta: (String) -> Unit): String = body(onDelta)
    }
    @Test fun resumedPartialIsSeparatedFromNewGenerationAndFinalAnswerReplacesBoth() = runBlocking {
        val q = queue(); val r = running(q)
        q.checkpoint(r.id, "未完成的旧输出")
        q.saveCheckpoint(r.id, org.json.JSONObject().put("version", 1).put("phase", "ready").toString())
        q.recover(); q.resume(r.id); var active = q.claim()!!
        FriendRunWorker(q, execution { delta -> delta("新生成的内容"); throw java.io.IOException() }, { true }).execute(active)
        val failed = q.runs().single()
        assertEquals("未完成的旧输出\n\n---\n从检查点继续的新输出\n\n新生成的内容", failed.partial)
        q.resume(r.id); active = q.claim()!!
        FriendRunWorker(q, execution { delta -> delta("最终结果"); "完整权威结果" }, { true }).execute(active)
        assertEquals("完整权威结果", q.runs().single().partial)
    }
    @Test fun rawDeltasAreAppendedAndSavedBeforeFailure() = runBlocking {
        val q = queue(); val run = running(q)
        FriendRunWorker(q, execution { delta -> delta("hello"); delta(" world"); throw java.io.IOException("sk-secret") }, { true }).execute(run)
        val saved = q.runs().single()
        assertEquals("hello world", saved.partial); assertEquals("failed", saved.state)
        assertEquals("network_failed", saved.errorCode); assertFalse(saved.errorMessage.contains("sk-secret"))
    }
    @Test fun finalReturnIsAuthoritativeAndNotAppendedTwice() = runBlocking {
        val q = queue(); val run = running(q)
        FriendRunWorker(q, execution { delta -> delta("one"); delta("two"); "onetwo" }, { true }).execute(run)
        assertEquals("onetwo", q.runs().single().partial); assertEquals("completed", q.runs().single().state)
    }
    @Test fun snapshotsAreThrottledButFailureFlushesLastTokens() = runBlocking {
        val memory = FriendRunMemory(); val q = FriendRunQueue(memory, Any()); val run = running(q)
        var clock = 0L; var writesBefore = 0
        FriendRunWorker(q, execution { delta ->
            writesBefore = memory.writes
            repeat(100) { delta("x") }; assertEquals(writesBefore, memory.writes)
            clock = 401; delta("last"); assertEquals(writesBefore + 1, memory.writes)
            throw java.io.IOException()
        }, { true }, { clock }).execute(run)
        assertEquals("x".repeat(100) + "last", q.runs().single().partial)
        assertEquals(writesBefore + 2, memory.writes)
    }
    @Test fun preflightFailureDoesNotInvokeRuntimeAndPreservesSafeDiagnostic() = runBlocking {
        val q = queue(); val run = running(q); var invoked = false
        val model = FriendModelFailure("CONFIG_MODEL", "请先配置 API 密钥和模型", "support-id")
        FriendRunWorker(q, execution({ throw model }) { invoked = true; "bad" }, { true }).execute(run)
        assertFalse(invoked); val saved = q.runs().single()
        assertEquals("CONFIG_MODEL", saved.errorCode); assertEquals("support-id", saved.diagnosticId)
        assertEquals(model.userMessage, saved.errorMessage)
    }
    @Test fun cancellationWinsOverLateReturn() = runBlocking {
        val q = queue(); val run = running(q)
        try {
            FriendRunWorker(q, execution { delta -> delta("partial"); q.cancel(run.id); "late result" }, { true }).execute(run)
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertEquals("cancelled", q.runs().single().state); assertEquals("partial", q.runs().single().partial)
    }
    @Test fun revokedAuthorizationCancelsWithoutInvokingRuntime() = runBlocking {
        val q = queue(); val run = running(q); var invoked = false
        try { FriendRunWorker(q, execution { invoked = true; "bad" }, { false }).execute(run) }
        catch (_: CancellationException) { }
        assertFalse(invoked); assertEquals("cancelled", q.runs().single().state)
    }
    @Test fun coroutineCancellationPreservesPartialAndDoesNotRequeue() = runBlocking {
        val q = queue(); val run = running(q); val entered = CompletableDeferred<Unit>()
        val job = launch {
            FriendRunWorker(q, execution { delta -> delta("saved partial"); entered.complete(Unit); CompletableDeferred<String>().await() }, { true }).execute(run)
        }
        entered.await(); job.cancelAndJoin()
        assertEquals("interrupted", q.runs().single().state); assertEquals("saved partial", q.runs().single().partial)
        assertNull(q.claim())
    }
    @Test fun unknownExceptionCannotLeakInputsCredentialsOrUrls() = runBlocking {
        val q = queue(); val run = running(q)
        FriendRunWorker(q, execution { error("Authorization: Bearer sk-secret https://private/input") }, { true }).execute(run)
        assertEquals("execution_failed", q.runs().single().errorCode)
        assertFalse(q.runs().single().errorMessage.contains("sk-secret"))
    }
}
