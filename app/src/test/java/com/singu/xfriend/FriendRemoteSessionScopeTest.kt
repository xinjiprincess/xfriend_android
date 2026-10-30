package com.singu.xfriend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local protocol regressions: a newly paired actor must never execute an earlier actor's intent. */
class FriendRemoteSessionScopeTest {
    private class Memory(initial: FriendRemoteSaved) : FriendRemoteStorage {
        private var value = initial
        @Synchronized override fun read() = value
        @Synchronized override fun write(value: FriendRemoteSaved) { this.value = value }
        val saved get() = read()
    }

    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun session(server: MockWebServer, cookie: String = "xf_mobile=old-pair") =
        FriendRemoteSession("http://127.0.0.1:${server.port}", cookie)
    private fun bound(session: FriendRemoteSession, id: String = "original-request", runId: String = "") =
        FriendRemoteRun(id, "expert1", "original task", runId = runId,
            sessionScope = friendRemoteSessionScope(session))
    private fun capabilities(cancel: Boolean = true) = MockResponse().setBody(
        """{"application":"xfriend","protocol":2,"features":{"runs":true,"event_replay":true,"cancel":$cancel,"approvals":true}}"""
    )
    private fun resources(name: String = "Expert") = MockResponse().setBody(
        """{"resources":[{"id":"expert1","name":"$name","kind":"expert"}]}"""
    )
    private fun setup(server: MockWebServer) {
        server.enqueue(capabilities())
        server.enqueue(resources())
    }
    private fun pairResponse() = MockResponse().setResponseCode(303)
        .addHeader("Set-Cookie", "xf_mobile=fresh-pair; Path=/; HttpOnly")
    private fun commonResponse(request: RecordedRequest): MockResponse? = when (request.path) {
        "/mobile/pair" -> pairResponse()
        "/mobile/v2/capabilities" -> capabilities()
        "/mobile/v2/resources" -> resources()
        else -> null
    }
    private fun requests(server: MockWebServer): List<RecordedRequest> =
        (0 until server.requestCount).map {
            server.takeRequest(2, TimeUnit.SECONDS) ?: error("Expected recorded local request")
        }

    @Test fun scopeIncludesCookieAndAccountWithoutStoringEitherInTheKey() {
        val first = FriendRemoteSession("https://bridge.example", "xf_mobile=first-secret")
        val equivalent = first.copy(origin = " https://bridge.example/ ")
        val second = first.copy(cookie = "xf_mobile=second-secret")
        assertEquals(friendRemoteSessionScope(first), friendRemoteSessionScope(equivalent))
        assertFalse(friendRemoteSessionScope(first) == friendRemoteSessionScope(second))
        assertEquals(remotePairedStorageScope("phone-a", first), remotePairedStorageScope("phone-a", equivalent))
        assertFalse(remotePairedStorageScope("phone-a", first) == remotePairedStorageScope("phone-a", second))
        assertFalse(remotePairedStorageScope("phone-a", first) == remotePairedStorageScope("phone-b", first))
        for (key in listOf(friendRemoteSessionScope(first), remotePairedStorageScope("phone-a", first))) {
            assertTrue(key.matches(Regex("[a-f0-9]{64}")))
            assertFalse(key.contains("first-secret"))
            assertFalse(key.contains("phone-a"))
        }
    }

    @Test fun localRunCodecKeepsBindingAndOldUnboundRecordsRemainReadable() {
        val paired = FriendRemoteSession("https://bridge.example", "xf_mobile=codec-secret")
        val original = bound(paired).copy(threadId = "original-thread", partial = "saved partial",
            attachmentsJson = """[{"path":"inbound/original.txt"}]""")
        val restored = FriendRemoteRun.parse(original.json())
        assertEquals(original, restored)
        assertEquals(friendRemoteSessionScope(paired), original.json().getString("session_scope"))
        assertTrue(restored.belongsTo(paired))
        assertFalse(restored.belongsTo(paired.copy(cookie = "xf_mobile=another-pair")))

        val legacy = original.json().apply { remove("session_scope") }
        val unbound = FriendRemoteRun.parse(legacy)
        assertEquals("", unbound.sessionScope)
        assertEquals(original.clientRequestId, unbound.clientRequestId)
        assertEquals(original.partial, unbound.partial)
        assertEquals(original.attachmentsJson, unbound.attachmentsJson)
        assertFalse(unbound.belongsTo(paired))
    }

    @Test fun changingSessionArchivesCurrentRunEvenWhenLegacyHistoryWasEmpty() {
        val first = FriendRemoteSession("https://bridge.example", "xf_mobile=first")
        val second = first.copy(cookie = "xf_mobile=second")
        val current = bound(first).copy(partial = "saved before pairing", approvalRequestId = "decision-id")
        val changed = FriendRemoteSaved(first, current).forSession(second)
        assertEquals(second, changed.session)
        assertNull(changed.run)
        assertEquals(listOf(current), changed.history)
        assertEquals(current.clientRequestId, changed.history.single().clientRequestId)
        assertEquals(current.approvalRequestId, changed.history.single().approvalRequestId)
        val unchanged = FriendRemoteSaved(first, current, listOf(current)).forSession(first)
        assertEquals(current, unchanged.run)
        assertEquals(1, unchanged.history.count { it.clientRequestId == current.clientRequestId })
    }

    @Test fun storageCompareAndSetRejectsAnotherCookieBeforeTransforming() {
        val first = FriendRemoteSession("https://bridge.example", "xf_mobile=first")
        val second = first.copy(cookie = "xf_mobile=second")
        val original = FriendRemoteSaved(second, bound(second))
        val memory = Memory(original)
        assertNull(memory.updateForSession(first) { fail("Old session transform was executed"); it })
        assertEquals(original, memory.saved)
        val updated = memory.updateForSession(second) { it.copy(run = it.run!!.copy(partial = "new actor result")) }
        assertEquals("new actor result", updated!!.run!!.partial)
        assertEquals(updated, memory.saved)
    }

    @Test fun backgroundObservationKeepsOnlyCurrentBoundRunsAndPreservesOtherActorsHistory() {
        val first = FriendRemoteSession("https://bridge.example", "xf_mobile=first")
        val second = first.copy(cookie = "xf_mobile=second")
        val archived = bound(first, "same-request", "old-run").copy(state = "running")
        val current = bound(second, "same-request", "new-run").copy(state = "running")
        val legacy = FriendRemoteRun("legacy-request", "expert1", "old input", "legacy-run", state = "running")
        val completed = bound(second, "completed-request", "completed-run").copy(state = "completed")
        val saved = FriendRemoteSaved(second, current, listOf(archived, current, legacy, completed)).forSession(second)
        assertEquals(2, saved.history.count { it.clientRequestId == "same-request" })
        assertTrue(saved.history.contains(archived))
        assertTrue(saved.history.contains(legacy))
        assertEquals(listOf(current), saved.observableRuns())
    }

    @Test fun unboundPendingWithoutRunIdCannotBeSubmittedOrSelectedAfterRestore() = runBlocking {
        server().use { host ->
            val legacy = FriendRemoteRun("legacy-request", "expert1", "unconfirmed legacy input",
                partial = "locally saved text", attachmentsJson = """[{"path":"inbound/legacy.txt"}]""")
            val memory = Memory(FriendRemoteSaved(session(host), legacy))
            setup(host)
            val controller = FriendRemoteController(memory)
            controller.restore()
            controller.submitPending()
            controller.selectRun(legacy)
            controller.submitPending()
            val retained = (memory.saved.history + listOfNotNull(memory.saved.run))
                .first { it.clientRequestId == legacy.clientRequestId }
            assertEquals(legacy.input, retained.input)
            assertEquals(legacy.partial, retained.partial)
            assertEquals(legacy.attachmentsJson, retained.attachmentsJson)
            assertEquals("", retained.sessionScope)
            assertNull(memory.saved.run)
            assertEquals(2, host.requestCount)
            assertTrue(requests(host).all { it.method == "GET" })
        }
    }

    @Test fun knownLegacyRunBindsOnlyAfterAnAuthoritativeMatchingSnapshot() = runBlocking {
        server().use { host ->
            val paired = session(host)
            val legacy = FriendRemoteRun("legacy-request", "expert1", "old input", "known-run", partial = "old partial")
            val memory = Memory(FriendRemoteSaved(paired, legacy, listOf(legacy)))
            setup(host)
            host.enqueue(MockResponse().setBody("""{"run_id":"known-run","state":"completed","output":"confirmed result"}"""))
            FriendRemoteController(memory).restore()
            val restored = memory.saved.run!!
            assertTrue(restored.belongsTo(paired))
            assertEquals("legacy-request", restored.clientRequestId)
            assertEquals("known-run", restored.runId)
            assertEquals("confirmed result", restored.partial)
            assertEquals(1, memory.saved.history.count { it.clientRequestId == "legacy-request" })
            val sent = requests(host)
            assertEquals(3, sent.size)
            assertTrue(sent.all { it.method == "GET" })
            assertEquals("/mobile/v2/runs/known-run", sent.last().path)
        }
    }

    @Test fun deniedOrMismatchedLegacySnapshotNeverBindsOrReplaysTheOldIntent() = runBlocking {
        val responses = listOf(
            MockResponse().setResponseCode(403),
            MockResponse().setResponseCode(404),
            MockResponse().setBody("""{"run_id":"someone-else","state":"completed","output":"unrelated"}"""),
        )
        for (response in responses) server().use { host ->
            val legacy = FriendRemoteRun("legacy-request", "expert1", "old input", "known-run", partial = "preserved partial")
            val memory = Memory(FriendRemoteSaved(session(host), legacy))
            setup(host)
            host.enqueue(response)
            FriendRemoteController(memory).restore()
            val retained = (memory.saved.history + listOfNotNull(memory.saved.run))
                .first { it.clientRequestId == "legacy-request" }
            assertEquals("", retained.sessionScope)
            assertEquals("preserved partial", retained.partial)
            assertEquals("known-run", retained.runId)
            assertEquals(3, host.requestCount)
            assertTrue(requests(host).all { it.method == "GET" })
        }
    }

    @Test fun sameCookieRetryKeepsPersistedRequestThreadAndAttachmentsWithoutAutomaticPost() = runBlocking {
        server().use { host ->
            val paired = session(host)
            val original = bound(paired).copy(threadId = "original-thread",
                attachmentsJson = """[{"path":"inbound/original.txt","filename":"original.txt","media_type":"text/plain"}]""")
            val memory = Memory(FriendRemoteSaved(paired, original, listOf(original)))
            setup(host)
            val controller = FriendRemoteController(memory)
            controller.restore()
            assertEquals(2, host.requestCount)
            host.enqueue(MockResponse().setBody("""{"run_id":"confirmed-run","thread_id":"original-thread","state":"queued"}"""))
            host.enqueue(MockResponse().setBody("""{"run_id":"confirmed-run","state":"completed","output":"done"}"""))
            controller.submitPending()
            val sent = requests(host)
            val post = sent.single { it.method == "POST" }
            val body = JSONObject(post.body.readUtf8())
            assertEquals("/mobile/v2/runs", post.path)
            assertEquals(original.clientRequestId, body.getString("client_request_id"))
            assertEquals(original.threadId, body.getString("thread_id"))
            assertEquals(original.attachmentsJson, body.getJSONArray("attachments").toString())
            assertFalse(body.has("session_scope"))
            assertEquals(paired.cookie, post.getHeader("Cookie"))
            assertTrue(memory.saved.run!!.belongsTo(paired))
            assertEquals("done", memory.saved.run!!.partial)
        }
    }

    @Test fun pairingAnotherCookieAtSameOriginCannotExecuteTheArchivedPendingRun() = runBlocking {
        server().use { host ->
            val first = session(host)
            val original = bound(first).copy(partial = "saved partial")
            val memory = Memory(FriendRemoteSaved(first, original))
            setup(host)
            val controller = FriendRemoteController(memory)
            controller.restore()
            host.enqueue(pairResponse())
            setup(host)
            controller.pair(first.origin, "local-pair-code-123456")
            assertEquals("xf_mobile=fresh-pair", memory.saved.session!!.cookie)
            assertNull(memory.saved.run)
            assertTrue(memory.saved.history.contains(original))
            controller.selectRun(original)
            controller.submitPending()
            controller.cancel()
            controller.decideApproval("old-approval", "a".repeat(64), "approve")
            assertNull(memory.saved.run)
            assertEquals(5, host.requestCount)
            val posts = requests(host).filter { it.method == "POST" }
            assertEquals(listOf("/mobile/pair"), posts.map { it.path })
        }
    }

    @Test fun lateSubmissionResponseCannotRestoreAnEarlierActorsExecutablePointer() = runBlocking {
        server().use { host ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            host.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/mobile/v2/runs" && request.method == "POST") {
                        entered.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        return MockResponse().setBody("""{"run_id":"late-run","thread_id":"old-thread","state":"queued"}""")
                    }
                    return commonResponse(request) ?: MockResponse().setResponseCode(404)
                }
            }
            val first = session(host)
            val original = bound(first)
            val memory = Memory(FriendRemoteSaved(first, original))
            val controller = FriendRemoteController(memory)
            controller.restore()
            val old = launch(Dispatchers.Default) { controller.submitPending() }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                controller.pair(first.origin, "local-pair-code-123456")
                release.countDown()
                withTimeout(10_000) { old.join() }
                assertEquals("xf_mobile=fresh-pair", memory.saved.session!!.cookie)
                assertNull(memory.saved.run)
                assertTrue(memory.saved.history.any { it.clientRequestId == original.clientRequestId && it.sessionScope == original.sessionScope })
                assertTrue(controller.state.value.ready)
                val sent = requests(host)
                assertEquals(1, sent.count { it.path == "/mobile/v2/runs" })
                assertTrue(sent.none { it.path?.startsWith("/mobile/v2/runs/late-run") == true })
                assertEquals(first.cookie, sent.single { it.path == "/mobile/v2/runs" }.getHeader("Cookie"))
            } finally {
                release.countDown()
                old.cancelAndJoin()
            }
        }
    }

    @Test fun lateSnapshotCannotOverwriteNewPairingOrSubscribeToTheOldRun() = runBlocking {
        server().use { host ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            host.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/mobile/v2/runs/old-run") {
                        entered.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        return MockResponse().setBody("""{"run_id":"old-run","state":"running","output":"old actor response"}""")
                    }
                    if (request.path?.startsWith("/mobile/v2/runs/old-run/events") == true) {
                        return MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"run_id\":\"old-run\",\"seq\":1,\"type\":\"state\",\"payload\":{\"state\":\"completed\"}}\n\n" +
                                "data: {\"run_id\":\"old-run\",\"seq\":2,\"type\":\"done\",\"payload\":{\"output\":\"old actor response\"}}\n\n"
                        )
                    }
                    return commonResponse(request) ?: MockResponse().setResponseCode(404)
                }
            }
            val first = session(host)
            val original = bound(first, runId = "old-run").copy(state = "running", partial = "saved old partial")
            val memory = Memory(FriendRemoteSaved(first, original))
            val controller = FriendRemoteController(memory)
            val old = launch(Dispatchers.Default) { controller.restore() }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                controller.pair(first.origin, "local-pair-code-123456")
                release.countDown()
                withTimeout(10_000) { old.join() }
                assertEquals("xf_mobile=fresh-pair", memory.saved.session!!.cookie)
                assertNull(memory.saved.run)
                assertTrue(memory.saved.history.any { it.clientRequestId == original.clientRequestId && it.partial == original.partial })
                assertTrue(controller.state.value.ready)
                assertTrue(requests(host).none { it.path?.contains("/events") == true })
            } finally {
                release.countDown()
                old.cancelAndJoin()
            }
        }
    }
}
