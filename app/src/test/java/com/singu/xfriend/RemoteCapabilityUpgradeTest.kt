package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class RemoteCapabilityUpgradeTest {
    private class Memory(var value: FriendRemoteSaved): FriendRemoteStorage {
        override fun read() = value
        override fun write(value: FriendRemoteSaved) { this.value = value }
    }
    @Test fun continuedConversationPreservesThreadAttachmentsAndHistory() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val session = FriendRemoteSession("http://127.0.0.1:${server.port}", "xf_mobile=secret")
            val old = FriendRemoteRun("first", "expert", "first", "old", "thread-one", "completed")
            val memory = Memory(FriendRemoteSaved(session, old, listOf(old)))
            server.enqueue(MockResponse().setBody("""{"application":"xfriend","protocol":2,"features":{"runs":true,"event_replay":true,"cancel":true}}"""))
            server.enqueue(MockResponse().setBody("""{"resources":[{"id":"expert","name":"Expert","kind":"expert"}]}"""))
            server.enqueue(MockResponse().setBody("""{"run_id":"old","thread_id":"thread-one","state":"completed","output":"old answer"}"""))
            val controller = FriendRemoteController(memory)
            controller.restore()
            server.enqueue(MockResponse().setBody("""{"run_id":"new","thread_id":"thread-one","state":"completed"}"""))
            server.enqueue(MockResponse().setBody("""{"run_id":"new","thread_id":"thread-one","state":"completed","output":"new answer"}"""))
            controller.send(FriendRemoteResource("expert", "Expert", "expert"), "continue", attachmentsJson = """[{"path":"inbound/report.pdf","filename":"report.pdf","media_type":"application/pdf"}]""")
            repeat(3) { server.takeRequest() }
            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("thread-one", payload.getString("thread_id"))
            assertEquals("inbound/report.pdf", payload.getJSONArray("attachments").getJSONObject(0).getString("path"))
            assertEquals(2, memory.value.history.size)
            assertEquals("first", memory.value.history.last().clientRequestId)
            assertEquals("new answer", memory.value.run!!.partial)
        }
    }
    @Test fun artifactLinksSelectOwnedRelativePathsOnly() {
        val links = remoteArtifactLinks("[report](outbound/report.pdf) [bad](../../private) [web](https://example.com/file) [encoded](/api/agents/a/workspace/download?path=outbound%2Ffinal.docx)")
        assertEquals(listOf("report" to "outbound/report.pdf", "encoded" to "outbound/final.docx"), links)
    }
    @Test fun attachmentsSurviveProcessStateSerialization() {
        val run = FriendRemoteRun("request", "expert", "task", attachmentsJson = """[{"path":"inbound/a.png"}]""")
        assertEquals(run, FriendRemoteRun.parse(run.json()))
    }
    @Test fun untrustedUpdatePublisherIsRejectedBeforePayloadParsing() {
        assertThrows(IllegalArgumentException::class.java) { verifyApplicationRelease(JSONObject().put("key_id", "other"), "xfriend", 0) }
    }
    @Test fun existingDesktopThreadAndMeetingControlsKeepTheirNativeIdentifiers() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"),0)
            val session=FriendRemoteSession("http://127.0.0.1:${server.port}","xf_mobile=secret")
            val client=FriendRemoteClient()
            server.enqueue(MockResponse().setBody("""[{"thread_id":"desktop-original","resource_id":"expert","title":"Desktop","last_active":1}]"""))
            assertEquals("desktop-original",client.threads(session,"expert").single().getString("thread_id"))
            server.enqueue(MockResponse().setBody("""{"thread_id":"desktop-original","messages":[{"role":"user","content":"existing"}],"has_more":false}"""))
            assertEquals("user：existing",remoteHistoryText(client.threadHistory(session,"expert","desktop-original").getJSONArray("messages")))
            server.enqueue(MockResponse().setBody("""[{"id":"meeting-original","state":"paused"}]"""))
            assertEquals("meeting-original",client.meetings(session).single().getString("id"))
            server.enqueue(MockResponse().setBody("""{"id":"meeting-original","state":"paused","round":1}"""))
            client.meeting(session,"meeting-original")
            repeat(4){assertEquals("GET",server.takeRequest().method)}
            server.enqueue(MockResponse().setBody("""{"id":"meeting-original","state":"running"}"""))
            client.controlMeeting(session,"meeting-original","start",JSONObject().put("skip",false))
            val request=server.takeRequest()
            assertEquals("/mobile/v2/meetings/meeting-original/start",request.path)
            assertEquals("POST",request.method)
            assertFalse(JSONObject(request.body.readUtf8()).getBoolean("skip"))
            assertEquals("xf_mobile=secret",request.getHeader("Cookie"))
        }
    }
}
