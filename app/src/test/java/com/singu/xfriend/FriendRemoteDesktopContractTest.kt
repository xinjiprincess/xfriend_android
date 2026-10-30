package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** Fixtures serialized by actual desktop Capabilities/Resources and SQLite RunStore (2026-10-14). */
class FriendRemoteDesktopContractTest {
    private val capabilities = """{"protocol":2,"application":"xfriend","features":{"runs":true,"event_replay":true,"cancel":true,"approvals":false}}"""
    private val resources = """{"resources":[{"id":"expert1","name":"Desktop Expert","kind":"expert","revision":"1"}]}"""
    private val snapshot = """{"run_id":"r1","thread_id":"t1","resource_id":"expert1","state":"running","output":"","error":null,"last_event_seq":2}"""
    private val sse = """id: 1
data: {"run_id":"r1","seq":1,"type":"state","payload":{"state":"queued"}}

id: 2
data: {"run_id":"r1","seq":2,"type":"state","payload":{"state":"running"}}

id: 3
data: {"run_id":"r1","seq":3,"type":"text_delta","payload":{"text":"actual desktop partial"}}

id: 4
data: {"run_id":"r1","seq":4,"type":"state","payload":{"state":"interrupted"}}

id: 5
data: {"run_id":"r1","seq":5,"type":"done","payload":{"output":"actual desktop partial"}}

"""
    private val finalSnapshot = """{"run_id":"r1","thread_id":"t1","resource_id":"expert1","state":"interrupted","output":"actual desktop partial","error":null,"last_event_seq":5}"""
    @Test fun consumesActualDesktopSnapshotAndSseWithoutResubmitting() = runBlocking {
        MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }.use { server ->
            val session = FriendRemoteSession("http://127.0.0.1:${server.port}", "xfriend_mobile=paired")
            var saved = FriendRemoteSaved(session, FriendRemoteRun("req", "expert1", "input", "r1"))
            val storage = object : FriendRemoteStorage {
                override fun read() = saved
                override fun write(value: FriendRemoteSaved) { saved = value }
            }
            server.enqueue(MockResponse().setBody(capabilities))
            server.enqueue(MockResponse().setBody(resources))
            server.enqueue(MockResponse().setBody(snapshot))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(sse))
            val controller = FriendRemoteController(storage)
            controller.restore()
            assertEquals("Desktop Expert", controller.state.value.resources.single().name)
            assertEquals("interrupted", saved.run!!.state)
            assertEquals("actual desktop partial", saved.run!!.partial)
            assertEquals(5L, saved.run!!.cursor)
            assertTrue(saved.run!!.terminal)
            val requests = (1..4).map { server.takeRequest() }
            assertEquals("/mobile/v2/runs/r1/events?after_seq=0", requests.last().path)
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Origin") == session.origin && it.getHeader("Cookie") == session.cookie })
            server.enqueue(MockResponse().setBody(finalSnapshot))
            assertEquals("interrupted", FriendRemoteClient().snapshot(session, "r1").getString("state"))
            server.enqueue(MockResponse().setBody(finalSnapshot))
            FriendRemoteClient().cancel(session, "r1")
            server.takeRequest()
            val cancel = server.takeRequest()
            assertEquals("POST", cancel.method); assertEquals("/mobile/v2/runs/r1/cancel", cancel.path)
        }
    }
}
