package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendRunCheckpointTest {
    private fun ready() = JSONObject().put("version", 1).put("phase", "ready").put("turn", 2)
    private fun started(): Pair<FriendRunQueue, FriendRun> {
        val q = FriendRunQueue(FriendRunMemory(), Any()); q.enqueue("e", "i", false, "a")
        return q to q.claim()!!
    }
    @Test fun actualRuntimeStepsPendingContractRequiresReview() = runBlocking<Unit> {
        val (q, r) = started(); val cp = FriendRunRuntimeCheckpoint(q, r.id) { true }
        cp.save(JSONObject().put("version", 1).put("status", "running").put("steps",
            JSONObject().put("expert-1", JSONObject().put("pending", JSONObject().put("call_id", "tool-1").put("path", "out.md").put("sha256", "hash")))))
        q.recover()
        assertEquals("requires_review", q.runs().single().state)
        assertThrows(IllegalArgumentException::class.java) { q.resume(r.id) }
    }
    @Test fun changedRuntimeConfigCannotOfferContinueButton() {
        val (q, r) = started(); q.saveCheckpoint(r.id, ready().toString())
        q.finish(r.id, "partial", "failed", "CHECKPOINT_CONFIG_CHANGED", "配置已变化")
        assertFalse(q.runs().single().canResume)
        assertThrows(IllegalArgumentException::class.java) { q.resume(r.id) }
    }
    @Test fun explicitResumeKeepsRunIdPartialAndConfirmedToolResults() = runBlocking<Unit> {
        val (q, r) = started(); val cp = FriendRunRuntimeCheckpoint(q, r.id) { true }
        cp.save(ready().put("tool_results", JSONObject().put("read_file", "confirmed output")))
        q.checkpoint(r.id, "prior text"); q.recover()
        assertTrue(q.runs().single().canResume)
        val continued = q.resume(r.id)
        assertEquals(r.id, continued.id); assertEquals("prior text", continued.partial)
        assertEquals("queued", continued.state); assertEquals(continued.id, q.resume(r.id).id)
        q.claim(); assertEquals("confirmed output", cp.load()!!.getJSONObject("tool_results").getString("read_file"))
    }
    @Test fun missingCheckpointCannotPretendToResume() = runBlocking<Unit> {
        val (q, r) = started(); q.recover()
        assertFalse(q.runs().single().canResume)
        assertThrows(IllegalArgumentException::class.java) { q.resume(r.id) }
    }
    @Test fun pendingEffectRecoveryRequiresReviewWithoutAutomaticReplay() = runBlocking<Unit> {
        val (q, r) = started()
        FriendRunRuntimeCheckpoint(q, r.id) { true }.save(ready().put("phase", "pending_effect").put("pending_tool", "send_message"))
        q.recover()
        assertEquals("requires_review", q.runs().single().state); assertFalse(q.runs().single().canResume)
        assertThrows(IllegalArgumentException::class.java) { q.resume(r.id) }
        assertNull(q.claim())
        val restarted = q.enqueue("e", "i", false, "b", r.id)
        assertEquals(r.id, restarted.parentId); assertNull(restarted.checkpoint)
    }
    @Test fun cancelPendingEffectStopsAndPreservesReviewWarning() = runBlocking<Unit> {
        val (q, r) = started(); q.saveCheckpoint(r.id, ready().put("phase", "pending_effect").toString())
        q.cancel(r.id); assertEquals("requires_review", q.runs().single().state)
        q.finish(r.id, "last text", "interrupted", errorMessage = "stopped")
        assertEquals("requires_review", q.runs().single().state); assertEquals("last text", q.runs().single().partial)
    }
    @Test fun logoutPendingEffectStopsAllTasksAndPreservesReviewEvidence() = runBlocking<Unit> {
        val (q, r) = started(); q.saveCheckpoint(r.id, ready().put("phase", "pending_effect").toString())
        q.enqueue("e", "queued", false, "b"); q.cancelAll()
        assertEquals(listOf("requires_review", "cancelled"), q.runs().map { it.state })
        assertTrue(q.runs().none { it.active })
    }
    @Test fun checkpointLoadIsDefensiveCopy() = runBlocking<Unit> {
        val (q, r) = started(); val cp = FriendRunRuntimeCheckpoint(q, r.id) { true }; cp.save(ready())
        cp.load()!!.put("turn", 999)
        assertEquals(2, cp.load()!!.getInt("turn"))
    }
    @Test fun credentialsAreRejectedAtAnyNestingLevel() = runBlocking<Unit> {
        listOf("apiKey", "api_key", "access-token", "refresh_token", "Authorization", "portableSecrets").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { validateFriendCheckpoint(ready().put("model", JSONObject().put(key, "sk-secret"))) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateFriendCheckpoint(JSONObject().put("phase", "unknown").put("version", 1)) }
    }
    @Test fun cancelledOrRevokedWorkerCannotOverwriteCheckpoint() = runBlocking<Unit> {
        val (q, r) = started(); var allowed = true
        val cp = FriendRunRuntimeCheckpoint(q, r.id) { allowed }; cp.save(ready())
        allowed = false
        assertThrows(CancellationException::class.java) { runBlocking { cp.save(ready().put("turn", 9)) } }
        allowed = true; q.cancel(r.id)
        assertThrows(CancellationException::class.java) { runBlocking { cp.save(ready().put("turn", 9)) } }
        assertEquals(2, JSONObject(q.runs().single().checkpoint!!).getInt("turn"))
    }
    @Test fun ciphertextContainsFullCheckpointAndCannotExposePlainToolOutput() = runBlocking<Unit> {
        val key = javax.crypto.KeyGenerator.getInstance("AES").generateKey()
        val crypto = FriendRunCrypto({ key }, "test")
        val r = FriendRun(FriendRunIds.newId(), "e", "i", false, "a", checkpoint = ready().put("result", "private tool output").toString())
        val encrypted = crypto.encrypt(FriendRunCodec.encode(listOf(r)))
        assertFalse(encrypted.contains("private tool output"))
        assertEquals(r, FriendRunCodec.decode(crypto.decrypt(encrypted)).single())
    }
}
