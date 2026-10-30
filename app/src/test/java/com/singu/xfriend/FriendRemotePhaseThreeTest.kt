package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** Authenticated contract cases authored for phase three; intentionally not run. */
class FriendRemotePhaseThreeTest {
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun paired(server: MockWebServer) = FriendRemoteSession("http://127.0.0.1:${server.port}", "xf_mobile=pair-secret")

    @Test fun materialManifestAndOriginalDownloadUseScopeAndPairingOnly() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("""{"kind":"meeting","scope_id":"meeting-1","materials":[],"executions":[]}"""))
            server.enqueue(MockResponse().setBody("original-bytes"))
            val session = paired(server)
            val destination = File.createTempFile("xf-material-test-", ".bin")
            try {
                val client = FriendRemoteClient()
                client.materialManifest(session, "meeting", "meeting-1")
                client.materialDownload(session, "material-2", destination)
                val manifest = server.takeRequest()
                val download = server.takeRequest()
                assertEquals("/mobile/v2/conversation-materials/scopes/meeting/meeting-1", manifest.path)
                assertEquals("/mobile/v2/conversation-materials/material-2", download.path)
                for (request in listOf(manifest, download)) {
                    assertEquals(session.cookie, request.getHeader("Cookie"))
                    assertEquals(session.origin, request.getHeader("Origin"))
                    assertNull(request.getHeader("Authorization"))
                }
                assertEquals("original-bytes", destination.readText())
            } finally { destination.delete() }
        }
    }

    @Test fun flowchartExportSendsTheReviewedCanonicalDocument() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("flowchart TD\nA[Reviewed]"))
            val destination = File.createTempFile("xf-diagram-test-", ".mmd")
            try {
                val document = JSONObject().put("format", "xfriend-diagram").put("version", 1)
                    .put("revision", 7).put("source", "flowchart TD\nA[Reviewed]").put("mode", "source-only")
                FriendRemoteClient().diagramExport(paired(server), "expert-1", document, "mmd", destination)
                val sent = server.takeRequest()
                val body = JSONObject(sent.body.readUtf8())
                assertEquals("/mobile/v2/resources/expert-1/diagrams/export", sent.path)
                assertEquals(7, body.getJSONObject("document").getInt("revision"))
                assertEquals("flowchart TD\nA[Reviewed]", body.getJSONObject("document").getString("source"))
                assertEquals("mmd", body.getString("format"))
                assertEquals("flowchart TD\nA[Reviewed]", destination.readText())
            } finally { destination.delete() }
        }
    }

    @Test fun invitationDeclineIsExplicitAndAvatarUsesOnlyPairedResourcePath() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("image-bytes"))
            val client = FriendRemoteClient()
            val session = paired(server)
            client.invitationDecision(session, "invite-3", false)
            client.avatar(session, "expert-4")
            val decision = server.takeRequest()
            val avatar = server.takeRequest()
            assertEquals("/mobile/v2/rooms/invitations/invite-3/respond", decision.path)
            assertEquals(false, JSONObject(decision.body.readUtf8()).getBoolean("accept"))
            assertEquals("/mobile/v2/resources/expert-4/avatar", avatar.path)
            assertNull(avatar.getHeader("Authorization"))
            assertEquals(session.cookie, avatar.getHeader("Cookie"))
        }
    }
}
