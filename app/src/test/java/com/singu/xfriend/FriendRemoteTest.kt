package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import kotlin.system.measureTimeMillis
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class FriendRemoteTest {
    private val caps = """{"application":"xfriend","protocol":2,"features":{"runs":true,"event_replay":true,"cancel":true,"approvals":false}}"""
    private class Memory(var saved: FriendRemoteSaved = FriendRemoteSaved()) : FriendRemoteStorage {
        val writes = mutableListOf<FriendRemoteSaved>()
        override fun read() = saved
        override fun write(value: FriendRemoteSaved) { saved = value; writes += value }
    }
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun session(server: MockWebServer) = FriendRemoteSession("http://127.0.0.1:${server.port}", "xf_mobile=secret")
    private fun event(seq: Int, type: String, payload: String) = """{"run_id":"r1","seq":$seq,"type":"$type","payload":$payload}"""
    private fun stream(vararg rows: String) = MockResponse().addHeader("Content-Type", "text/event-stream").setBody(rows.joinToString("") { "data: $it\n\n" })
    private fun setup(s: MockWebServer) {
        s.enqueue(MockResponse().setBody(caps))
        s.enqueue(MockResponse().setBody("""{"resources":[{"id":"expert1","name":"研究专家","kind":"expert"}]}"""))
    }
    @Test fun pairingDoesNotFollowRedirectAndUsesOnlyBridgeOrigin() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "https://elsewhere.invalid/collect").addHeader("Set-Cookie", "xfriend_mobile=token; Path=/; HttpOnly"))
            val result = FriendRemoteClient().pair(session(s).origin, "1234567890123456")
            assertEquals("xfriend_mobile=token", result.cookie)
            val request = s.takeRequest()
            assertEquals(result.origin, request.getHeader("Origin"))
            assertEquals("code=1234567890123456", request.body.readUtf8())
            assertNull(request.getHeader("Authorization"))
            assertEquals(1, s.requestCount)
            assertFalse(result.toString().contains("token"))
        }
    }
    @Test fun refusesCleartextLanAndCrossDomainCookie() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { FriendRemoteClient().pair("http://192.168.1.5", "1234567890123456") } }
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(303).addHeader("Set-Cookie", "xf_mobile=secret; Domain=elsewhere.invalid; Path=/"))
            assertThrows(FriendRemoteFailure::class.java) { runBlocking { FriendRemoteClient().pair(session(s).origin, "1234567890123456") } }
        }
        Unit
    }
    @Test fun rejectsOldProtocolAndMissingReplay() {
        assertThrows(FriendRemoteFailure::class.java) { friendRemoteCapabilities(JSONObject(caps.replace("\"protocol\":2", "\"protocol\":1"))) }
        assertThrows(FriendRemoteFailure::class.java) { friendRemoteCapabilities(JSONObject(caps.replace("\"event_replay\":true", "\"event_replay\":false"))) }
    }
    @Test fun capabilityErrorsDistinguishMissingInterfaceFromDeniedAccess() = runBlocking {
        server().use { s ->
            for ((status, code) in listOf(401 to "401", 403 to "403", 404 to "upgrade_required")) {
                s.enqueue(MockResponse().setResponseCode(status))
                val failure = assertThrows(FriendRemoteFailure::class.java) {
                    runBlocking { FriendRemoteClient().capabilities(session(s)) }
                }
                assertEquals(code, failure.code)
                if (status == 403) assertTrue(failure.message!!.contains("授权"))
                if (status == 404) assertTrue(failure.message!!.contains("升级"))
            }
        }
    }
    @Test fun replayDeduplicatesAndKeepsExactText() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("req", "expert1", "hello", "r1", state = "running", cursor = 1, partial = "你")))
            setup(s)
            s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"running","output":"你"}"""))
            s.enqueue(stream(event(1,"text_delta","""{"text":"你"}"""), event(2,"text_delta","""{"text":"好"}"""), event(3,"state","""{"state":"completed"}"""), event(4,"done","""{"output":"你好！"}""")))
            val controller = FriendRemoteController(storage)
            controller.restore()
            assertEquals("你好！", storage.saved.run!!.partial)
            assertEquals(4L, storage.saved.run!!.cursor)
            assertTrue(storage.saved.run!!.terminal)
            val requests = (1..4).map { s.takeRequest() }
            assertEquals("/mobile/v2/runs/r1", requests[2].path)
            assertEquals("/mobile/v2/runs/r1/events?after_seq=1", requests[3].path)
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Cookie") == "xf_mobile=secret" && it.getHeader("Origin") == session(s).origin })
        }
    }
    @Test fun restoredTerminalRunDoesNotSubscribeOrResubmit() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("req", "expert1", "hi", "r1")))
            setup(s); s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"completed","output":"saved answer"}"""))
            FriendRemoteController(storage).restore()
            assertEquals("saved answer", storage.saved.run!!.partial)
            assertEquals(3, s.requestCount)
        }
    }
    @Test fun failedSnapshotPreservesAlreadyStreamedText() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("req", "expert1", "hi", "r1", partial = "saved partial")))
            setup(s); s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"failed","output":"","error":{"code":"model_error"}}"""))
            FriendRemoteController(storage).restore()
            assertEquals("saved partial", storage.saved.run!!.partial)
            assertTrue(storage.saved.run!!.terminal); assertTrue(storage.saved.run!!.error.contains("模型"))
        }
    }
    @Test fun lostSubmissionResponseReusesPersistedRequestId() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s)))
            val c = FriendRemoteController(storage)
            setup(s); c.restore()
            s.enqueue(MockResponse().setResponseCode(503))
            c.send(FriendRemoteResource("expert1","Expert","expert"), "write something")
            val pending = storage.saved.run!!
            assertTrue(pending.clientRequestId.isNotEmpty()); assertEquals("", pending.runId)
            val first = (1..3).map { s.takeRequest() }.last()
            assertEquals(pending.clientRequestId, JSONObject(first.body.readUtf8()).getString("client_request_id"))
            val restored = FriendRemoteController(storage)
            setup(s); restored.restore()
            assertEquals(5, s.requestCount) // Restore does not automatically repeat an uncertain submit.
            s.takeRequest(); s.takeRequest()
            s.enqueue(MockResponse().setBody("""{"run_id":"r1","thread_id":"t1","state":"queued"}"""))
            s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"completed","output":"ok"}"""))
            restored.submitPending()
            val retry = s.takeRequest()
            assertEquals(pending.clientRequestId, JSONObject(retry.body.readUtf8()).getString("client_request_id"))
            assertEquals("ok", storage.saved.run!!.partial)
            assertEquals(1, storage.writes.count { it.run?.runId == "" })
        }
    }
    @Test fun incompleteFrameDoesNotAdvanceCheckpoint() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("req","expert1","hi","r1")))
            setup(s); s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"running"}"""))
            s.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: ${event(1,"text_delta","""{"text":"partial"}""")}\n\ndata: ${event(2,"text_delta","""{"text":"lost"}""")}"))
            val c = FriendRemoteController(storage); c.restore()
            assertEquals("partial", storage.saved.run!!.partial); assertEquals(1L, storage.saved.run!!.cursor)
            assertEquals("running", storage.saved.run!!.state) // Observer EOF is not a host interruption.
            assertFalse(storage.saved.run!!.terminal); assertTrue(c.state.value.notice.contains("断开"))
        }
    }
    @Test fun rejectsGapAndWrongTask() {
        val r = FriendRemoteRun("req","expert1","hi","r1")
        assertThrows(FriendRemoteFailure::class.java) { friendRemoteEvent(r, JSONObject(event(2,"text_delta","""{"text":"no"}"""))) }
        assertThrows(FriendRemoteFailure::class.java) { friendRemoteEvent(r, JSONObject(event(1,"text_delta","""{"text":"no"}""").replace("r1","other"))) }
    }
    @Test fun revokedSessionShowsReadableMessageWithoutServerBody() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s)))
            s.enqueue(MockResponse().setResponseCode(401).setBody("private token and trace"))
            val c = FriendRemoteController(storage); c.restore()
            assertTrue(c.state.value.notice.contains("配对已失效")); assertFalse(c.state.value.notice.contains("private"))
        }
    }
    @Test fun errorsInEventsAreRetainedAndNeverBecomeFakeSuccess() {
        val r = friendRemoteEvent(FriendRemoteRun("req","expert1","hi","r1"), JSONObject(event(1,"error","""{"code":"model_error","message":"private content"}""")))
        assertFalse(r.terminal); assertTrue(r.error.contains("模型")); assertFalse(r.error.contains("private"))
    }
    @Test fun logoutBarrierCancelsOnlyTheOldAccountAndRejectsLateWrites() {
        val old = Job(); val other = Job()
        val generation = FriendRemoteSessions.generation("test-account-old")
        FriendRemoteSessions.register("test-account-old", old)
        FriendRemoteSessions.register("test-account-other", other)
        val next = FriendRemoteSessions.invalidate("test-account-old")
        assertTrue(old.isCancelled); assertTrue(other.isActive)
        assertThrows(CancellationException::class.java) { FriendRemoteSessions.access("test-account-old", generation) { fail("Late write") } }
        assertThrows(CancellationException::class.java) { FriendRemoteSessions.access("test-account-old", next) { fail("Cleanup pending") } }
        FriendRemoteSessions.cleared("test-account-old", next)
        assertEquals("new", FriendRemoteSessions.access("test-account-old", next) { "new" })
        assertThrows(CancellationException::class.java) { FriendRemoteSessions.access("test-account-old", generation) { fail("Old observer resurrected") } }
        other.cancel()
    }
    @Test fun logoutUsesOnlyPairedOriginAndCookie() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(204))
            FriendRemoteClient().logout(session(s))
            val r = s.takeRequest()
            assertEquals("POST", r.method); assertEquals("/mobile/logout", r.path)
            assertEquals(session(s).origin, r.getHeader("Origin")); assertEquals("xf_mobile=secret", r.getHeader("Cookie"))
            assertNull(r.getHeader("Authorization"))
        }
    }
    @Test fun leavingPageCancelsOnlyObservationAndKeepsLastReplayCursor() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
                .setBody("data: ${event(1,"text_delta","""{"text":"saved"}""")}\n\n")
                .setHeader("Content-Length", "100000")) // Socket stays open waiting for more events.
            var saved = FriendRemoteRun("req", "expert1", "hi", "r1")
            val received = CompletableDeferred<Unit>()
            val observer = launch {
                FriendRemoteClient().events(session(s), saved) {
                    saved = friendRemoteEvent(saved, it); received.complete(Unit)
                }
            }
            withTimeout(3000) { received.await(); observer.cancelAndJoin() }
            assertEquals("saved", saved.partial); assertEquals(1L, saved.cursor)
            assertFalse(saved.terminal)
            assertEquals(1, s.requestCount)
            assertEquals("GET", s.takeRequest().method) // No remote cancel request on page disposal.
        }
    }
    @Test fun stalledLogoutHasBoundedNetworkLifetime() = runBlocking {
        server().use { s ->
            s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val elapsed = measureTimeMillis {
                assertThrows(java.io.IOException::class.java) { runBlocking { FriendRemoteClient().logout(session(s)) } }
            }
            assertTrue("Logout exceeded upper bound: $elapsed ms", elapsed < 9000)
        }
    }
    @Test fun obsoleteCleanupCannotDeleteNewSession() {
        val scope = "cleanup-race"
        val old = FriendRemoteSessions.invalidate(scope)
        val newer = FriendRemoteSessions.invalidate(scope)
        assertNull(FriendRemoteSessions.cleanup(scope, old) { fail("Obsolete cleanup ran") })
        assertEquals("deleted", FriendRemoteSessions.cleanup(scope, newer) { "deleted" })
        assertEquals("new session", FriendRemoteSessions.access(scope, newer) { "new session" })
        assertNull(FriendRemoteSessions.cleanup(scope, old) { fail("Deleted new credentials") })
    }
    @Test fun desktopTerminalDoneDoesNotTurnFailureOrInterruptionIntoSuccess() = runBlocking {
        for (state in listOf("failed", "cancelled", "interrupted")) server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("req", "expert1", "hi", "r1")))
            setup(s); s.enqueue(MockResponse().setBody("""{"run_id":"r1","thread_id":"t1","resource_id":"expert1","state":"running","output":"","error":null,"last_event_seq":0}"""))
            s.enqueue(stream(event(1,"text_delta","""{"text":"retained"}"""), event(2,"state","""{"state":"$state"}"""), event(3,"done","""{"output":"retained"}""")))
            val c = FriendRemoteController(storage); c.restore()
            assertEquals(state, storage.saved.run!!.state); assertTrue(storage.saved.run!!.terminal)
            assertEquals(3L, storage.saved.run!!.cursor); assertEquals("retained", storage.saved.run!!.partial)
        }
    }
    @Test fun interruptedSnapshotRetainsPartialAndAllowsExplicitNewTask() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("old-request", "expert1", "old input", "r1", partial = "saved partial")))
            setup(s)
            s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"interrupted","output":"","last_event_seq":5,"error":null}"""))
            val controller = FriendRemoteController(storage); controller.restore()
            assertEquals("interrupted", storage.saved.run!!.state)
            assertEquals("saved partial", storage.saved.run!!.partial)
            assertTrue(storage.saved.run!!.terminal)
            assertEquals(3, s.requestCount) // No SSE or automatic resubmission for an interrupted host run.
            repeat(3) { s.takeRequest() }
            s.enqueue(MockResponse().setBody("""{"run_id":"r2","thread_id":"t2","state":"queued"}"""))
            s.enqueue(MockResponse().setBody("""{"run_id":"r2","state":"completed","output":"new result"}"""))
            controller.send(FriendRemoteResource("expert1", "Expert", "expert"), "explicit new input")
            val submitted = JSONObject(s.takeRequest().body.readUtf8())
            assertNotEquals("old-request", submitted.getString("client_request_id"))
            assertEquals("explicit new input", submitted.getString("input"))
            assertEquals("new result", storage.saved.run!!.partial)
        }
    }
    @Test fun doneWithoutTerminalStateRequiresSnapshotConfirmation() = runBlocking {
        server().use { s ->
            val storage = Memory(FriendRemoteSaved(session(s), FriendRemoteRun("req", "expert1", "hi", "r1")))
            setup(s); s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"running"}"""))
            s.enqueue(stream(event(1, "done", """{"output":"partial"}""")))
            s.enqueue(MockResponse().setBody("""{"run_id":"r1","state":"interrupted","output":"partial"}"""))
            val c = FriendRemoteController(storage); c.restore()
            assertEquals("interrupted", storage.saved.run!!.state)
            assertEquals("partial", storage.saved.run!!.partial)
            assertEquals(5, s.requestCount)
        }
        val pending = FriendRemoteRun("req", "expert1", "hi", "r1", state = "running")
        assertFalse(friendRemoteEvent(pending, JSONObject(event(1,"done","""{"output":"not confirmed"}"""))).terminal)
    }
}
