package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class FriendRemoteWorkflowsTest {
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun session(server: MockWebServer) = FriendRemoteSession("http://127.0.0.1:${server.port}", "xf_mobile=secret")

    @Test fun meetingCreationUsesDefaultHostAndIdempotentRequestBody() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("""{"host":{"id":"builtin","name":"主持人"},"model_available":true}"""))
            server.enqueue(MockResponse().setBody("""{"id":"meeting-1","state":"draft"}"""))
            val client = FriendRemoteClient()
            val paired = session(server)
            assertTrue(client.meetingDefaultHost(paired).getBoolean("model_available"))
            val body = JSONObject().put("request_id", "stable-request").put("title", "规划")
                .put("topic", "季度目标").put("expert_ids", org.json.JSONArray(listOf("expert-1")))
            assertEquals("meeting-1", client.createMeeting(paired, body).getString("id"))
            val get = server.takeRequest()
            val post = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/mobile/v2/meetings/default-host", get.path)
            assertEquals("POST", post.method)
            assertEquals("/mobile/v2/meetings", post.path)
            assertEquals("stable-request", JSONObject(post.body.readUtf8()).getString("request_id"))
            for (request in listOf(get, post)) {
                assertEquals(paired.origin, request.getHeader("Origin"))
                assertEquals(paired.cookie, request.getHeader("Cookie"))
                assertNull(request.getHeader("Authorization"))
            }
        }
    }

    @Test fun exactTaskApprovalOptionIsPassedWithoutGuessingFromItsLabel() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val request = JSONObject().put("pending_id", "pending-7").put("option_id", "allow-once-42")
            FriendRemoteClient().answerTaskPermission(session(server), "tree-1", "node-3", request)
            val sent = server.takeRequest()
            val sentBody = JSONObject(sent.body.readUtf8())
            assertEquals("/mobile/v2/trees/tree-1/nodes/node-3/permission", sent.path)
            assertEquals("pending-7", sentBody.getString("pending_id"))
            assertEquals("allow-once-42", sentBody.getString("option_id"))
        }
    }

    @Test fun reportExportSendsPreviewDigestAndKeepsBinaryBytes() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").setBody("docx-bytes"))
            val destination = File.createTempFile("remote-report", ".docx")
            try {
                val body = JSONObject().put("format", "docx").put("mode", "all").put("indices", org.json.JSONArray())
                    .put("start", 0).put("end", 0).put("digest", "preview-digest")
                val mime = FriendRemoteClient().exportReport(session(server), "meetings", "meeting-1", "report-1", body, destination)
                val sent = server.takeRequest()
                assertEquals("/mobile/v2/meetings/meeting-1/reports/report-1/document", sent.path)
                assertEquals("preview-digest", JSONObject(sent.body.readUtf8()).getString("digest"))
                assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", mime)
                assertEquals("docx-bytes", destination.readText())
            } finally { destination.delete() }
        }
    }

    @Test fun transcriptExportUsesTheSourcePreviewDigestRoute() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "text/markdown").setBody("# Transcript\nfull conversation"))
            val destination = File.createTempFile("remote-transcript", ".md")
            try {
                FriendRemoteClient().exportTranscript(session(server), "rooms", "room-1",
                    JSONObject().put("format", "md").put("mode", "all").put("indices", org.json.JSONArray())
                        .put("start", 0).put("end", 0).put("digest", "source-digest"), destination)
                val sent = server.takeRequest()
                val body = JSONObject(sent.body.readUtf8())
                assertEquals("/mobile/v2/rooms/room-1/document", sent.path)
                assertEquals("source-digest", body.getString("digest"))
                assertEquals("all", body.getString("mode"))
                assertEquals("# Transcript\nfull conversation", destination.readText())
            } finally { destination.delete() }
        }
    }
}
