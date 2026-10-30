package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

internal class FriendRunMemory : FriendRunStorage {
    var values = emptyList<FriendRun>()
    var writes = 0
    var failWrite = false
    override fun read(): List<FriendRun> = values
    override fun write(runs: List<FriendRun>) {
        if (failWrite) throw java.io.IOException("disk full")
        values = runs.toList(); writes++
    }
}

class FriendRunQueueTest {
    @Test fun requestIdMakesEnqueueIdempotent() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val a = q.enqueue("expert", "private input", false, "client-1")
        assertEquals(a, q.enqueue("expert", "private input", false, "client-1"))
        assertEquals(1, q.runs().size)
        assertThrows(IllegalArgumentException::class.java) { q.enqueue("other", "private input", false, "client-1") }
    }
    @Test fun fifoAndAtMostOneRunningAcrossRepositoryHandles() {
        val memory = FriendRunMemory(); val lock = Any()
        val a = FriendRunQueue(memory, lock); val b = FriendRunQueue(memory, lock)
        val first = a.enqueue("expert", "first", false, "a"); val second = b.enqueue("team", "second", true, "b")
        assertEquals(first.id, b.claim()!!.id); assertNull(a.claim())
        a.finish(first.id, "answer", "completed", errorMessage = "已完成")
        assertEquals(second.id, b.claim()!!.id)
    }
    @Test fun concurrentEnqueuesDoNotLoseTasksAndConcurrentClaimsDoNotDuplicate() {
        val memory = FriendRunMemory(); val lock = Any()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 40).map { n -> pool.submit { FriendRunQueue(memory, lock).enqueue("e", "$n", false, "r$n") } }
            futures.forEach { it.get() }; assertEquals(40, memory.values.size)
            val claims = (0 until 8).map { pool.submit<FriendRun?> { FriendRunQueue(memory, lock).claim() } }
            assertEquals(1, claims.map { it.get() }.count { it != null })
        } finally { pool.shutdownNow() }
    }
    @Test fun processRecoveryPreservesPartialWithoutRequeueingTools() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val old = q.enqueue("e", "input", false, "a"); q.claim(); q.checkpoint(old.id, "partial")
        val queued = q.enqueue("e", "new", false, "b")
        q.recover()
        val restored = q.runs().first()
        assertEquals("interrupted", restored.state); assertEquals("partial", restored.partial)
        assertEquals("process_interrupted", restored.errorCode); assertTrue(restored.diagnosticId.isNotBlank())
        assertEquals(queued.id, q.claim()!!.id)
    }
    @Test fun explicitRetryCreatesLinkedRunWithoutTouchingOldPartial() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val old = q.enqueue("e", "input", false, "a"); q.claim(); q.checkpoint(old.id, "keep me"); q.recover()
        val retried = q.enqueue("e", "input", false, "b", old.id)
        assertNotEquals(old.id, retried.id); assertEquals(old.id, retried.parentId)
        assertEquals("", retried.partial); assertEquals("keep me", q.runs().first().partial)
        assertEquals(retried.id, q.enqueue("e", "input", false, "b", old.id).id)
    }
    @Test fun cannotRetryActiveOrCompletedRun() {
        val q = FriendRunQueue(FriendRunMemory(), Any()); val r = q.enqueue("e", "i", false, "a")
        assertThrows(IllegalArgumentException::class.java) { q.enqueue("e", "i", false, "b", r.id) }
        q.claim(); q.finish(r.id, "ok", "completed", errorMessage = "ok")
        assertThrows(IllegalArgumentException::class.java) { q.enqueue("e", "i", false, "b", r.id) }
    }
    @Test fun cancelWinsOverLateSuccessAndRetainsLastPartial() {
        val q = FriendRunQueue(FriendRunMemory(), Any()); val r = q.enqueue("e", "i", false, "a")
        q.claim(); q.cancel(r.id)
        assertFalse(q.checkpoint(r.id, "ignored"))
        assertFalse(q.finish(r.id, "useful partial", "completed", errorMessage = "done"))
        assertEquals("cancelled", q.runs().single().state); assertEquals("useful partial", q.runs().single().partial)
    }
    @Test fun cancellationDoesNotChangeCompletedRuns() {
        val q = FriendRunQueue(FriendRunMemory(), Any()); val r = q.enqueue("e", "i", false, "a")
        q.claim(); q.finish(r.id, "ok", "completed", errorMessage = "done")
        val completed = q.runs().single(); q.cancel(r.id); q.cancelAll(); q.recover()
        assertEquals(completed, q.runs().single())
    }
    @Test fun cancelledQueuedRunCannotBeClaimed() {
        val q = FriendRunQueue(FriendRunMemory(), Any()); val r = q.enqueue("e", "i", false, "a")
        q.cancel(r.id); assertNull(q.claim())
    }
    @Test fun diskFailureDoesNotPretendRunWasQueued() {
        val memory = FriendRunMemory(); val q = FriendRunQueue(memory, Any()); memory.failWrite = true
        assertThrows(java.io.IOException::class.java) { q.enqueue("e", "i", false, "a") }
        assertTrue(q.runs().isEmpty())
    }
    @Test fun invalidIdsAndBlankInputsAreRejected() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        assertThrows(IllegalArgumentException::class.java) { q.enqueue("../e", "i", false, "a") }
        assertThrows(IllegalArgumentException::class.java) { q.enqueue("e", " ", false, "a") }
        assertThrows(IllegalArgumentException::class.java) { q.cancel("../../file") }
        assertThrows(IllegalArgumentException::class.java) { FriendRunIds.scope("A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { FriendRunIds.scope("a".repeat(63)) }
        FriendRunIds.scope("a".repeat(64))
    }
    @Test fun codecRoundTripIncludesDiagnosticsAndParent() {
        val run = FriendRun(FriendRunIds.newId(), "e", "隐私\n内容", true, "req", "failed", "部分结果", "HTTP_401", "diag", "密钥失效", parentId = FriendRunIds.newId())
        assertEquals(listOf(run), FriendRunCodec.decode(FriendRunCodec.encode(listOf(run))))
        assertFalse(run.toString().contains("隐私")); assertFalse(run.toString().contains("部分结果"))
        assertThrows(IllegalArgumentException::class.java) { FriendRunCodec.decode(FriendRunCodec.encode(listOf(run, run))) }
    }
}
