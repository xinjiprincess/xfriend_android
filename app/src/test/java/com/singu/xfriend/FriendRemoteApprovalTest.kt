package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class FriendRemoteApprovalTest {
    private val hash = "a".repeat(64)
    private val approval get() = JSONObject().put("approval_id", "a1").put("summary_hash", hash)
        .put("actions", org.json.JSONArray("""[{"name":"write_file","args":{"path":"report.md","text":"approved content"}}]"""))
        .put("allowed_decisions", org.json.JSONArray("""["approve","reject"]"""))
        .put("desktop_confirmation_required", false)
    private class Memory(var saved: FriendRemoteSaved) : FriendRemoteStorage {
        val writes = mutableListOf<FriendRemoteSaved>()
        override fun read() = saved
        override fun write(value: FriendRemoteSaved) { saved = value; writes += value }
    }
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun pending(s: MockWebServer) = Memory(FriendRemoteSaved(FriendRemoteSession("http://127.0.0.1:${s.port}", "xfriend_mobile=paired"),
        FriendRemoteRun("original", "expert1", "hi", "r1", state = "awaiting_approval", partial = "before approval", approvalJson = approval.toString())))
    private fun prepare(s: MockWebServer, supported: Boolean = true, detail: JSONObject = approval) {
        s.enqueue(MockResponse().setBody("""{"protocol":2,"application":"xfriend","features":{"runs":true,"event_replay":true,"cancel":true,"approvals":$supported}}"""))
        s.enqueue(MockResponse().setBody("""{"resources":[{"id":"expert1","name":"Expert","kind":"expert","revision":"1"}]}"""))
        s.enqueue(MockResponse().setBody(JSONObject().put("run_id","r1").put("state","awaiting_approval").put("output","before approval").put("approval",detail).toString()))
        s.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(": keepalive\n\n"))
    }
    private fun finish(s: MockWebServer) {
        s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"queued","approval":null}"""))
        s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"completed","output":"actual result","approval":null}"""))
    }
    @Test fun approveAndRejectBindExactSummaryAndNeverEditToolArguments() = runBlocking {
        for (decision in listOf("approve", "reject")) server().use { s ->
            val memory = pending(s); val c = FriendRemoteController(memory)
            prepare(s); c.restore(); repeat(4) { s.takeRequest() }
            finish(s); c.decideApproval("a1", hash, decision)
            val r = s.takeRequest(); val body = JSONObject(r.body.readUtf8())
            assertEquals("POST",r.method); assertEquals("/mobile/v2/runs/r1/approvals/a1",r.path)
            assertEquals(setOf("client_request_id","summary_hash","decision"),body.keys().asSequence().toSet())
            assertEquals(hash,body.getString("summary_hash")); assertEquals(decision,body.getString("decision"))
            assertEquals("xfriend_mobile=paired",r.getHeader("Cookie")); assertEquals(memory.saved.session!!.origin,r.getHeader("Origin"))
            assertTrue(memory.writes.any { it.run?.approvalRequestId == body.getString("client_request_id") && it.run?.approvalDecision == decision })
            assertEquals("completed",memory.saved.run!!.state); assertNull(memory.saved.run!!.approval)
        }
    }
    @Test fun lostDecisionResponseRestoresAndReusesIdempotencyKey() = runBlocking {
        server().use { s ->
            val memory = pending(s); val c = FriendRemoteController(memory)
            prepare(s); c.restore(); repeat(4) { s.takeRequest() }
            s.enqueue(MockResponse().setResponseCode(503)); c.decideApproval("a1",hash,"approve")
            val original = JSONObject(s.takeRequest().body.readUtf8()).getString("client_request_id")
            assertEquals(original,memory.saved.run!!.approvalRequestId)
            val restored = FriendRemoteController(memory)
            prepare(s); restored.restore(); repeat(4) { s.takeRequest() }
            assertEquals(original,memory.saved.run!!.approvalRequestId)
            finish(s); restored.decideApproval("a1",hash,"approve")
            assertEquals(original,JSONObject(s.takeRequest().body.readUtf8()).getString("client_request_id"))
        }
    }
    @Test fun disabledCapabilityDesktopOnlyAndStaleSummaryNeverSubmit() = runBlocking {
        for (mode in listOf("disabled","desktop","stale")) server().use { s ->
            val memory = pending(s); val c = FriendRemoteController(memory)
            val detail = approval.put("desktop_confirmation_required",mode == "desktop")
            prepare(s,mode != "disabled",detail); c.restore()
            c.decideApproval("a1",if (mode == "stale") "b".repeat(64) else hash,"approve")
            assertEquals(4,s.requestCount); assertEquals("awaiting_approval",memory.saved.run!!.state)
            assertTrue(c.state.value.notice.isNotEmpty())
        }
    }
    @Test fun changedSummaryClearsOldPendingDecisionAndDoesNotAutoApprove() {
        val old = FriendRemoteRun("req","expert1","hi","r1",state="awaiting_approval",approvalJson=approval.toString(),approvalRequestId="pending",approvalDecision="approve")
        val snapshot = JSONObject().put("run_id","r1").put("state","awaiting_approval").put("approval",approval.put("summary_hash","b".repeat(64)))
        val next = friendRemoteSnapshot(old,snapshot)
        assertEquals("",next.approvalRequestId); assertEquals("",next.approvalDecision)
        assertEquals("b".repeat(64),next.approval!!.summaryHash); assertFalse(next.terminal)
    }
    @Test fun approvalConflictKeepsPartialAndRequestsFreshReview() = runBlocking {
        server().use { s ->
            val memory = pending(s); val c = FriendRemoteController(memory)
            prepare(s); c.restore()
            s.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"SLASH_BAD_ARGS","message":"private trace","details":{"reason":"APPROVAL_STALE"}}}"""))
            c.decideApproval("a1",hash,"approve")
            assertEquals("before approval",memory.saved.run!!.partial)
            assertTrue(c.state.value.notice.contains("已改变")); assertFalse(c.state.value.notice.contains("private"))
        }
    }
    @Test fun oldStoredRunsWithoutApprovalRemainReadable() {
        val j=FriendRemoteRun("req","expert1","hi","r1").json()
        j.remove("approval");j.remove("approval_request_id");j.remove("approval_decision")
        assertNull(FriendRemoteRun.parse(j).approval)
    }
}
