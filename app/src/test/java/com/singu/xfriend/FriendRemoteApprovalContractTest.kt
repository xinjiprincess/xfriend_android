package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** Current desktop Approval + SQLite pause/claim serialization, not a live model test. */
class FriendRemoteApprovalContractTest {
    private val capabilities = """{"protocol":2,"application":"xfriend","features":{"runs":true,"event_replay":true,"cancel":true,"approvals":false}}"""
    private val approval = """{"approval_id":"a1","summary_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","actions":[{"name":"write_file","args":{"path":"report.md","text":"explicit content"}}],"allowed_decisions":["approve","reject"],"desktop_confirmation_required":false}"""
    private val snapshot = """{"run_id":"r1","thread_id":"t1","resource_id":"expert1","state":"awaiting_approval","output":"before actual approval","error":null,"last_event_seq":5,"approval":{"approval_id":"a1","summary_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","actions":[{"name":"write_file","args":{"path":"report.md","text":"explicit content"}}],"allowed_decisions":["approve","reject"],"desktop_confirmation_required":false}}"""
    private val sse = """id: 1
data: {"run_id":"r1","seq":1,"type":"state","payload":{"state":"queued"}}

id: 2
data: {"run_id":"r1","seq":2,"type":"state","payload":{"state":"running"}}

id: 3
data: {"run_id":"r1","seq":3,"type":"text_delta","payload":{"text":"before actual approval"}}

id: 4
data: {"run_id":"r1","seq":4,"type":"approval","payload":{"approval_id":"a1","summary_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","actions":[{"name":"write_file","args":{"path":"report.md","text":"explicit content"}}],"allowed_decisions":["approve","reject"],"desktop_confirmation_required":false}}

id: 5
data: {"run_id":"r1","seq":5,"type":"state","payload":{"state":"awaiting_approval"}}

"""
    private val after_decision = """{"run_id":"r1","thread_id":"t1","resource_id":"expert1","state":"queued","output":"before actual approval","error":null,"last_event_seq":6,"approval":null}"""
    private val finalSnapshot = """{"run_id":"r1","thread_id":"t1","resource_id":"expert1","state":"completed","output":"before actual approval","error":null,"last_event_seq":9,"approval":null}"""
    @Test fun productionClientConsumesActualApprovalAndDecisionResponses() = runBlocking {
        MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"),0) }.use { server ->
            val session = FriendRemoteSession("http://127.0.0.1:${server.port}", "xfriend_mobile=paired")
            val client = FriendRemoteClient()
            server.enqueue(MockResponse().setBody(capabilities))
            val features = client.capabilities(session)
            assertEquals(JSONObject(capabilities).getJSONObject("features").getBoolean("approvals"),features.approvals)
            server.enqueue(MockResponse().setBody(snapshot))
            var saved = friendRemoteSnapshot(FriendRemoteRun("req","expert1","input","r1"),client.snapshot(session,"r1"))
            assertEquals("awaiting_approval",saved.state); assertFalse(saved.terminal)
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream; charset=utf-8").setBody(sse))
            // Replay starts at 0, so the authoritative full output is not pre-appended to deltas.
            client.events(session,saved) { saved=friendRemoteEvent(saved,it) }
            assertEquals("before actual approval",saved.partial)
            assertEquals("a1",saved.approval!!.id)
            assertEquals(setOf("approve","reject"),saved.approval!!.allowedDecisions)
            server.enqueue(MockResponse().setBody(after_decision))
            val decision=client.approval(session,"r1",saved.approval!!,"reject","decision-1")
            saved=friendRemoteSnapshot(saved,decision)
            assertEquals("queued",saved.state); assertNull(saved.approval)
            repeat(3) { server.takeRequest() }
            val post=server.takeRequest()
            assertEquals("/mobile/v2/runs/r1/approvals/a1",post.path)
            val body=JSONObject(post.body.readUtf8())
            assertEquals("decision-1",body.getString("client_request_id"));assertEquals("reject",body.getString("decision"))
            assertEquals(JSONObject(approval).getString("summary_hash"),body.getString("summary_hash"))
            assertEquals(3,body.length())
            server.enqueue(MockResponse().setBody(finalSnapshot))
            saved=friendRemoteSnapshot(saved,client.snapshot(session,"r1"))
            assertTrue(saved.terminal);assertEquals("completed",saved.state)
        }
    }
}
