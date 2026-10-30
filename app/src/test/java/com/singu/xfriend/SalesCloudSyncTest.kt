package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SalesCloudSyncTest {
    private fun entity(text: String, revision: Long = 0) = SalesCloudEntity("customer", "one", JSONObject().put("name", text).toString(), revision = revision)
    private fun page(vararg entities: SalesCloudEntity, cursor: Long = entities.lastOrNull()?.revision ?: 0, more: Boolean = false) = JSONObject()
        .put("changes", JSONArray(entities.map { it.json() })).put("cursor", cursor).put("has_more", more)
    private fun ack(op: SalesCloudOperation, revision: Long, status: String = "applied") = JSONObject().put("cursor", 999)
        .put("results", JSONArray().put(JSONObject().put("operation_id", op.id).put("revision", revision).put("status", status)))

    @Test fun pushAcknowledgementNeverAdvancesPullCursor() {
        val e = entity("local"); val state = SalesCloudState(cursor = 3).capture(mapOf(e.key to e)) { "stable" }
        val next = state.acknowledge(state.outbox, ack(state.outbox.single(), 9), mapOf(e.key to e))
        assertEquals(3L, next.cursor); assertTrue(next.outbox.isEmpty()); assertEquals(9L, next.known[e.key]!!.revision)
    }
    @Test fun operationSurvivesRestartAndNewEditWithoutMutation() {
        val e = entity("first"); val state = SalesCloudState().capture(mapOf(e.key to e)) { "stable" }
        val restored = SalesCloudState.read(JSONObject(state.json().toString())).capture(mapOf(e.key to entity("second")))
        assertEquals(state.outbox, restored.outbox)
        assertFalse(restored.outbox.single().json().has("_local_fingerprint"))
        val next = restored.acknowledge(restored.outbox, ack(restored.outbox.single(), 1), mapOf(e.key to entity("second")))
            .capture(mapOf(e.key to entity("second"))) { "new-id" }
        assertEquals("new-id", next.outbox.single().id); assertEquals(1L, next.outbox.single().entity.revision)
    }
    @Test fun missingAcknowledgementDoesNotDropOutbox() {
        val e = entity("local"); val state = SalesCloudState().capture(mapOf(e.key to e))
        assertThrows(IllegalArgumentException::class.java) { state.acknowledge(state.outbox, JSONObject().put("results", JSONArray()), mapOf(e.key to e)) }
        assertEquals(1, state.outbox.size)
    }
    @Test fun unknownDesktopPayloadRoundTripsWithoutSpuriousUpload() {
        val local = entity("A")
        val remote = local.copy(payload = JSONObject(local.payload).put("_xfpack", JSONObject().put("files", JSONArray().put("src/a.py")))
            .put("catalog", JSONObject().put("license", "MIT")).toString(), revision = 8)
        val state = SalesCloudState(known = mapOf(remote.key to remote)).capture(mapOf(local.key to local))
        assertTrue(state.outbox.isEmpty())
        val changed = state.capture(mapOf(local.key to entity("B")))
        val payload = JSONObject(changed.outbox.single().entity.payload)
        assertEquals("MIT", payload.getJSONObject("catalog").getString("license"))
        assertEquals("src/a.py", payload.getJSONObject("_xfpack").getJSONArray("files").getString(0))
    }
    @Test fun modelConsentCannotBeInheritedFromCloud() {
        val previous = SalesCloudEntity("model_profile", "m", """{"syncSecret":true,"key":"secret","nested":{"access_token":"auth","apiKey":"old"}}""")
        val local = previous.copy(payload = """{"name":"model","sync_secrets":false}""")
        val outgoing = JSONObject(salesCloudOutgoingPayload(local, previous))
        assertFalse(outgoing.has("key")); assertFalse(outgoing.getJSONObject("nested").has("apiKey")); assertFalse(outgoing.getJSONObject("nested").has("access_token"))
    }
    @Test fun explicitModelConsentIncludesKeyButNeverAuthToken() {
        val local = SalesCloudEntity("model_profile", "m", """{"syncSecret":true,"key":"model-key","refresh_token":"auth","nested":{"authorization":"bearer"}}""")
        val outgoing = JSONObject(salesCloudOutgoingPayload(local, null))
        assertEquals("model-key", outgoing.getString("key")); assertFalse(outgoing.has("refresh_token")); assertFalse(outgoing.getJSONObject("nested").has("authorization"))
    }
    @Test fun multipleRemoteVersionsInPageApplyLatestWithoutFalseConflict() {
        val result = SalesCloudState().pull(page(entity("first", 1), entity("second", 2)), emptyMap())
        assertEquals("second", JSONObject(result.apply.single().payload).getString("name")); assertTrue(result.state.activeConflicts.isEmpty()); assertEquals(2L, result.state.cursor)
    }
    @Test fun conflictingPullRetainsBothVersionsAndOriginalOperation() {
        val local = entity("local"); val state = SalesCloudState().capture(mapOf(local.key to local)) { "proposal" }
        val result = state.pull(page(entity("cloud", 3)), mapOf(local.key to local)) { "conflict" }
        assertTrue(result.apply.isEmpty()); assertTrue(result.state.outbox.isEmpty())
        val conflict = SalesCloudState.read(JSONObject(result.state.json().toString())).activeConflicts.single()
        assertEquals(local, conflict.local); assertEquals("proposal", conflict.proposal!!.id); assertEquals(entity("cloud", 3), conflict.remote)
    }
    @Test fun acknowledgedRevisionProtectsAgainstOldPullAndTombstone() {
        val current = entity("new", 8)
        val result = SalesCloudState(known = mapOf(current.key to current), observed = mapOf(current.key to current.fingerprint))
            .pull(page(entity("old", 2).copy(deleted = true)), mapOf(current.key to current))
        assertTrue(result.apply.isEmpty()); assertEquals(current, result.state.known[current.key]); assertEquals(2L, result.state.cursor)
    }
    @Test fun emptyPageCannotSkipUnseenChanges() {
        assertThrows(IllegalArgumentException::class.java) { SalesCloudState().pull(page(cursor = 5), emptyMap()) }
    }
    @Test fun localDeleteUsesKnownRevision() {
        val e = entity("before", 4)
        val result = SalesCloudState(known = mapOf(e.key to e), observed = mapOf(e.key to e.fingerprint)).capture(emptyMap())
        assertTrue(result.outbox.single().entity.deleted); assertEquals(4L, result.outbox.single().entity.revision)
    }
    @Test fun batchLimitCountsUtf8EnvelopeAndKeepsPendingUnchanged() {
        val identity = SalesCloudIdentity("scope", "owner", "space", "device")
        val op = SalesCloudOperation("one", entity("中".repeat(100)))
        val limit = salesCloudPushBody(identity, listOf(op)).toString().toByteArray().size
        val queue = listOf(op, op.copy(id = "two"))
        assertEquals(listOf(op), salesCloudBatch(identity, queue, limit)); assertEquals(2, queue.size)
    }
    @Test fun mediaChunkBoundsCoverPartialAndExactChunks() {
        assertEquals(262144, salesCloudChunkLength(262145, 0)); assertEquals(1, salesCloudChunkLength(262145, 1))
        assertEquals(262144, salesCloudChunkLength(524288, 1))
        assertThrows(IllegalArgumentException::class.java) { salesCloudChunkLength(524288, 2) }
        assertThrows(IllegalArgumentException::class.java) { salesCloudChunkLength(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { salesCloudMediaId("../escape") }
    }
    @Test fun mediaManifestRejectsWrongHashIdAndLayout() {
        val j = JSONObject().put("media_id", "m").put("sha256", "a".repeat(64)).put("total_size", 262145).put("chunk_size", 262144).put("chunk_count", 2).put("uploaded_chunks", JSONArray().put(0))
        salesCloudMediaManifest(j, "m", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { salesCloudMediaManifest(j, "other") }
        assertThrows(IllegalArgumentException::class.java) { salesCloudMediaManifest(j, "m", "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { salesCloudMediaManifest(JSONObject(j.toString()).put("chunk_count", 1), "m") }
    }
    @Test fun fullFileHashDetectsCorruptedResumeBytes() {
        val file = File.createTempFile("sync-test-", ".part")
        try {
            file.writeText("abc"); assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", salesCloudFileHash(file))
            file.appendText("x"); assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", salesCloudFileHash(file))
        } finally { file.delete() }
    }
    @Test fun retryAfterLostResponseUsesExactSameOperation() = runBlocking {
        val e = entity("local")
        var saved = SalesCloudState(); val snapshot = mapOf(e.key to e)
        val local = object : SalesCloudLocal {
            override fun load() = saved
            override fun update(action: (SalesCloudState, Map<String, SalesCloudEntity>) -> SalesCloudState): SalesCloudState { saved = SalesCloudState.read(JSONObject(action(saved, snapshot).json().toString())); return saved }
            override fun pull(response: JSONObject): SalesCloudState { saved = saved.pull(response, snapshot).state; return saved }
            override fun resolve(conflictId: String, keepLocal: Boolean) = error("unused")
        }
        var first: String? = null; var count = 0
        val transport = object : SalesCloudTransport {
            override suspend fun pull(cursor: Long, limit: Int) = page(cursor = cursor)
            override suspend fun push(operations: List<SalesCloudOperation>): JSONObject {
                val wire = operations.single().json().toString(); count++
                if (first == null) { first = wire; throw java.io.IOException("connection lost after commit") }
                assertEquals(first, wire); return ack(operations.single(), 7, "duplicate")
            }
        }
        val engine = SalesCloudSyncEngine(local, transport, SalesCloudIdentity("scope", "owner", "space", "device")) { 123 }
        try { engine.sync(); fail("expected network error") } catch (_: java.io.IOException) { }
        assertEquals(1, saved.outbox.size)
        engine.sync(); assertTrue(saved.outbox.isEmpty()); assertEquals(2, count); assertEquals(123L, saved.lastSuccess); assertEquals(0L, saved.cursor)
    }
    @Test fun mediaFakeServerRoundTripResumesBothDirectionsAndVerifiesBytes() = runBlocking {
        val original = File.createTempFile("media-original-", ".wav")
        val download = File.createTempFile("media-download-", ".part")
        try {
            original.writeBytes(ByteArray(SALES_CLOUD_CHUNK * 2 + 17) { (it % 251).toByte() })
            val manifest = JSONObject().put("media_id", "stable-id").put("sha256", salesCloudFileHash(original))
                .put("total_size", original.length()).put("chunk_size", SALES_CLOUD_CHUNK).put("chunk_count", 3).put("uploaded_chunks", JSONArray())
            val server = mutableMapOf<Int, ByteArray>(); val uploads = mutableListOf<Int>()
            try {
                salesCloudUploadChunks(original, manifest) { index, bytes ->
                    server[index] = bytes; uploads.add(index)
                    if (index == 0) throw java.io.IOException("response lost after server persisted first chunk")
                }; fail("disconnect expected")
            } catch (_: java.io.IOException) { }
            val resumed = JSONObject(manifest.toString()).put("uploaded_chunks", JSONArray(server.keys.toList()))
            salesCloudUploadChunks(original, resumed) { index, bytes -> server[index] = bytes; uploads.add(index) }
            assertEquals(listOf(0, 1, 2), uploads)
            var persisted = "[]"; val requests = mutableListOf<Int>()
            val firstProgress = JSONArray()
            try {
                salesCloudDownloadChunks(download, manifest, firstProgress, { index ->
                    requests.add(index); if (index == 1) throw java.io.IOException("download interrupted")
                    server.getValue(index)
                }, { persisted = firstProgress.toString() }); fail("disconnect expected")
            } catch (_: java.io.IOException) { }
            assertEquals("[0]", persisted)
            val restarted = JSONArray(persisted)
            salesCloudDownloadChunks(download, manifest, restarted, { index -> requests.add(index); server.getValue(index) }, { persisted = restarted.toString() })
            assertEquals(listOf(0, 1, 1, 2), requests)
            assertArrayEquals(original.readBytes(), download.readBytes())
            assertEquals(manifest.getString("sha256"), salesCloudFileHash(download))
            // A corrupted resumed block is rejected even though its progress says it was received.
            java.io.RandomAccessFile(download, "rw").use { it.writeByte(99) }
            try { salesCloudDownloadChunks(download, manifest, JSONArray(persisted), { error("must not fetch completed chunks") }, {}); fail("corruption accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { original.delete(); download.delete() }
    }

    @Test fun individualMediaFailuresKeepCrmSuccessAndNextAutomaticRunWorking() = runBlocking {
        var record = entity("first")
        var state = SalesCloudState(enabled = true, mediaEnabled = true)
        var revision = 0L
        val local = object : SalesCloudLocal {
            override fun load() = state
            override fun update(action: (SalesCloudState, Map<String, SalesCloudEntity>) -> SalesCloudState): SalesCloudState {
                state = action(state, mapOf(record.key to record)); return state
            }
            override fun pull(response: JSONObject): SalesCloudState { state = state.pull(response, mapOf(record.key to record)).state; return state }
            override fun resolve(conflictId: String, keepLocal: Boolean) = error("unused")
        }
        val transport = object : SalesCloudTransport {
            override suspend fun pull(cursor: Long, limit: Int) = page(cursor = cursor)
            override suspend fun push(operations: List<SalesCloudOperation>) = ack(operations.single(), ++revision)
        }
        val engine = SalesCloudSyncEngine(local, transport, SalesCloudIdentity("s", "o", "p", "d")) { revision + 100 }
        engine.sync()
        val success = state.lastSuccess
        val warnings = mutableListOf<String>(); val transferred = mutableListOf<String>()
        salesCloudMediaAttempt("too-large", warnings) { salesCloudMediaSize(104857601) }
        salesCloudMediaAttempt("missing", warnings) { throw SalesCloudHttpException(404, "media_not_found", "missing") }
        salesCloudMediaAttempt("quota", warnings) { throw SalesCloudHttpException(413, "media_quota_exceeded", "quota") }
        salesCloudMediaAttempt("network", warnings) { throw java.io.IOException("offline") }
        salesCloudMediaAttempt("healthy", warnings) { transferred.add("healthy") }
        assertEquals(4, warnings.size); assertEquals(listOf("healthy"), transferred)
        assertEquals(success, state.lastSuccess); assertTrue(state.enabled); assertTrue(state.outbox.isEmpty())
        assertEquals(record.fingerprint, state.known[record.key]!!.fingerprint)
        record = entity("edited after media failure")
        engine.sync()
        assertEquals(2L, revision); assertTrue(state.outbox.isEmpty()); assertTrue(state.lastSuccess > success)
        assertEquals(record.fingerprint, state.known[record.key]!!.fingerprint)
        salesCloudMediaSize(104857600) // Inclusive server limit.
    }
    @Test fun missingMediaKeepsLocalFilesAndContinuesOtherItems() = runBlocking {
        val original = File.createTempFile("kept-media-", ".wav")
        try {
            original.writeText("local recording")
            val links = SalesCloudMediaLinks(original.name, listOf("photo.jpg"))
            val warnings = mutableListOf<String>(); var nextVisited = false
            salesCloudMediaAttempt("remote missing", warnings) { throw SalesCloudHttpException(404, "media_not_found", "missing or forbidden") }
            salesCloudMediaAttempt("next", warnings) { nextVisited = true }
            assertEquals("local recording", original.readText()); assertEquals(listOf("photo.jpg"), links.attachments)
            assertTrue(nextVisited); assertEquals(1, warnings.size)
        } finally { original.delete() }
    }
    @Test fun mediaCancellationIsNotConvertedToAnIndividualWarning() = runBlocking {
        val warnings = mutableListOf<String>()
        try { salesCloudMediaAttempt("cancel", warnings) { throw kotlinx.coroutines.CancellationException("stopped") }; fail("cancellation swallowed") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(warnings.isEmpty())
    }
    @Test fun attachmentSnapshotRejectsRemovalReplacementAndDeletedRecord() {
        val original = SalesCloudMediaLinks("old.wav", listOf("old.jpg"))
        val restored = SalesCloudMediaLinks.read(JSONObject(original.json().toString()))
        assertNull(restored.associate(SalesCloudMediaLinks("", listOf("old.jpg")), "download.wav", true))
        assertNull(restored.associate(SalesCloudMediaLinks("new.wav", listOf("new.jpg")), "download.wav", true))
        assertNull(restored.associate(original.copy(attachments = emptyList()), "download.jpg", false))
        assertNull(restored.associate(null, "download.wav", true))
        // A second transfer attempt must retain the original persisted expectation.
        assertNull(SalesCloudMediaLinks.read(JSONObject(restored.json().toString())).associate(original.copy(file = ""), "download.wav", true))
    }
    @Test fun unchangedAttachmentSnapshotAssociatesWithoutReplacingExistingLinks() {
        val original = SalesCloudMediaLinks("new.wav", listOf("new.jpg"))
        val result = original.associate(original, "download.wav", true)!!
        assertEquals("new.wav", result.file); assertEquals(listOf("new.jpg", "download.wav"), result.attachments)
        assertEquals(result, result.associate(result, "download.wav", true))
        val empty = SalesCloudMediaLinks("", listOf("kept.jpg"))
        assertEquals(SalesCloudMediaLinks("download.wav", listOf("kept.jpg")), empty.associate(empty, "download.wav", true))
        // The post-association snapshot also rejects a later user removal.
        assertNull(result.associate(original, "download.wav", true))
    }

}
