package com.singu.xfriend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteCollaborationContractTest {
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun session(server: MockWebServer) = FriendRemoteSession("http://127.0.0.1:${server.port}", "xfriend_mobile=local-test-only")

    @Test fun capabilitiesAreExplicitAndMissingFeaturesDoNotBecomeSupported() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"application":"xfriend","protocol":2,"features":{"runs":true,"event_replay":true,"cancel":true,"approvals":true,"rooms":true,"meetings":false,"conversation_materials":true,"room_model_settings":true,"responsible_tasks":true,"models":true}}"""))
            val value = FriendRemoteClient().capabilities(session(host))
            assertTrue(value.rooms && value.materials && value.roomModelSettings && value.responsibleTasks && value.models)
            assertFalse(value.meetings || value.taskTrees || value.memory || value.diagrams || value.documentExports)
            assertEquals("/mobile/v2/capabilities", host.takeRequest().path)
        }
    }

    @Test fun settingsAndSeatPatchPreserveExplicitNullsAndSharedRevision() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"room_id":"room","coordination_mode":"manual","leader_participant_id":null,"coordinator_model":null,"settings_revision":5}"""))
            host.enqueue(MockResponse().setBody("""{"participant_id":"seat","model_mode":"inherit","model":"native-owned/model"}"""))
            val client = FriendRemoteClient()
            val paired = session(host)
            val saved = client.roomSettings(paired, "room", RemoteRoomSettings("manual", "old-leader", "old/model", 4))
            assertEquals(5L, saved.revision)
            assertNull(saved.leader); assertNull(saved.model)
            client.roomParticipantModel(paired, "room", "seat", "inherit", null, 5)
            val settings = host.takeRequest()
            val seat = host.takeRequest()
            assertEquals("PATCH", settings.method)
            assertEquals("/mobile/v2/rooms/room/settings", settings.path)
            val settingsBody = JSONObject(settings.body.readUtf8())
            assertTrue(settingsBody.isNull("leader_participant_id") && settingsBody.isNull("coordinator_model"))
            assertEquals(4, settingsBody.getInt("expected_revision"))
            assertEquals("/mobile/v2/rooms/room/participants/seat", seat.path)
            val seatBody = JSONObject(seat.body.readUtf8())
            assertEquals("inherit", seatBody.getString("model_mode"))
            assertTrue(seatBody.isNull("model")); assertEquals(5, seatBody.getInt("expected_revision"))
            for (request in listOf(settings, seat)) {
                assertEquals(paired.cookie, request.getHeader("Cookie"))
                assertEquals(paired.origin, request.getHeader("Origin"))
                assertNull(request.getHeader("Authorization"))
            }
        }
    }

    @Test fun modelCatalogHasOnlyDeclaredFieldsAndDoesNotProbeOrSetDefaults() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"models":[{"ref":"native-private/model","label":"My model","provider":"private","model":"model","api_key":"must-not-copy"}]}"""))
            val values = FriendRemoteClient().models(session(host))
            assertEquals("native-private/model", values.single().ref)
            assertFalse(values.single().toString().contains("must-not-copy"))
            val request = host.takeRequest()
            assertEquals("GET", request.method); assertEquals("/mobile/v2/models", request.path)
            assertEquals(1, host.requestCount)
        }
    }

    @Test fun preflightKeepsExplicitMentionsAndReportsUnavailableRoleWithoutFallback() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"room_id":"room","ready":false,"coordination_mode":"leader","settings_revision":8,"coordinator":{"role":"coordinator","name":"Leader","model":null,"source":"leader","ready":false,"error_code":"MODEL_AUTH_REQUIRED"},"participants":[{"role":"participant","participant_id":"seat","name":"Expert","model":"native-old/model","source":"override","ready":false,"error_code":"MODEL_UNAVAILABLE"}]}"""))
            val result = FriendRemoteClient().roomPreflight(session(host), "room", listOf("seat"))
            assertFalse(result.ready); assertEquals(2, result.checks.size)
            assertEquals("MODEL_AUTH_REQUIRED", result.coordinator!!.errorCode)
            assertEquals("native-old/model", result.participants.single().model)
            val request = host.takeRequest()
            assertEquals("/mobile/v2/rooms/room/preflight", request.path)
            assertEquals("seat", JSONObject(request.body.readUtf8()).getJSONArray("mentions").getString(0))
            assertEquals(1, host.requestCount)
        }
    }

    @Test fun canonicalReadabilityIsSeparateFromOriginalExtractorStatus() {
        val material = RemoteMaterial.parse(JSONObject().put("id", "material").put("filename", "mixed.zip")
            .put("status", "unsupported").put("content_status", "partial").put("content_reason", "mixed_members"))
        assertEquals("unsupported", material.status)
        assertEquals("partial", material.contentStatus)
        assertEquals("部分内容可读", material.readiness)
        val message = remoteRoomMessage("Review", "stable-request", listOf(material.id), listOf("seat"))
        assertEquals("material", message.getJSONArray("material_ids").getString(0))
        assertFalse(message.toString().contains("workspace_path"))
        assertEquals("stable-request", message.getString("idempotency_key"))
    }

    @Test fun stagedUploadDoesNotBindAnyRoomAndOnlyExplicitSendCarriesMaterialIds() = runBlocking {
        server().use { host ->
            val file = File.createTempFile("staged-material", ".txt")
            try {
                file.writeText("Authorized material")
                host.enqueue(MockResponse().setBody("""{"id":"m1","filename":"a.txt","status":"ready","content_status":"ready"}"""))
                host.enqueue(MockResponse().setBody("""{"run_id":"run"}"""))
                val client = FriendRemoteClient()
                val paired = session(host)
                val staged = client.uploadMaterial(paired, file, "a.txt", "text/plain")
                val upload = host.takeRequest()
                assertEquals("/mobile/v2/conversation-materials", upload.path)
                assertFalse(upload.body.readUtf8().contains("room_id"))
                client.roomSend(paired, "room", remoteRoomMessage("Use this", "same-request", listOf(staged.getString("id")), emptyList()))
                val send = host.takeRequest()
                assertEquals("/mobile/v2/rooms/room/messages", send.path)
                assertEquals("m1", JSONObject(send.body.readUtf8()).getJSONArray("material_ids").getString(0))
            } finally { file.delete() }
        }
    }

    @Test fun safeErrorsKeepDomainCodesAndNeverCopyServerSecrets() {
        val error = friendRemoteHttpFailure(400, JSONObject("""{"error":{"code":"MODEL_CONFIGURATION_REQUIRED","message":"secret-key","details":{"headers":{"Authorization":"secret-key"}}}}"""))
        assertEquals("MODEL_CONFIGURATION_REQUIRED", error.code)
        val message = error.message.orEmpty()
        assertTrue(message.contains("模型")); assertFalse(message.contains("secret-key"))
        val stale = friendRemoteHttpFailure(409, JSONObject("""{"error":{"code":"SLASH_BAD_ARGS","details":{"reason":"APPROVAL_STALE"}}}"""))
        assertTrue(stale.message!!.contains("已改变"))
    }

    @Test fun responsibleCreationUsesExistingProposalRouteAndSameRequestBodyWithoutStarting() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"experts":[{"expert_id":"leader","name":"Leader","default_model":"private/model"}],"models":[{"model_ref":"private/model"}]}"""))
            repeat(2) { host.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"tree","state":"draft","responsible":{"stage":"planning"}}""")) }
            val client = FriendRemoteClient()
            val paired = session(host)
            assertEquals("leader", client.responsibleCapabilities(paired).experts.single().id)
            val body = remoteResponsibleRequest("stable-proposal", "Prepare report", "leader", null, listOf("leader"), listOf("material"))
            repeat(2) { assertEquals("tree", client.createResponsible(paired, body).getString("id")) }
            assertEquals("/mobile/v2/trees/responsible/capabilities", host.takeRequest().path)
            val first = host.takeRequest(); val second = host.takeRequest()
            assertEquals("/mobile/v2/trees/responsible", first.path)
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertEquals(3, host.requestCount)
        }
    }

    @Test fun pairedPendingKeepsOriginalBodyWithoutPromotingUnknownLegacyRecords() {
        val paired = FriendRemoteSession("https://first.example", "xf_mobile=first")
        val first = remotePairedStorageScope("account-space", paired)
        val second = remotePairedStorageScope("account-space", FriendRemoteSession("https://second.example", paired.cookie))
        assertNotEquals(first, second)
        assertNotEquals(first, remotePairedStorageScope("other-account", paired))
        val repaired = remotePairedStorageScope("account-space", paired.copy(cookie = "xf_mobile=other-actor"))
        assertNotEquals(first, repaired)
        assertFalse(first.contains(paired.cookie))
        val original = """{"text":"pending","idempotency_key":"original-request","material_ids":["m1"]}"""
        val command = """{"request_id":"original-command","generation":3,"action":"pause"}"""
        val stored = mapOf("unknown-legacy" to original, "$first:room" to original, "$first:control" to command)
        assertNull(stored["$repaired:room"])
        assertNull(stored["$repaired:control"])
        assertEquals(original, stored["unknown-legacy"])
        assertEquals("original-request", JSONObject(stored["${remotePairedStorageScope("account-space", paired)}:room"]!!).getString("idempotency_key"))
        assertEquals(3, JSONObject(stored["$first:control"]!!).getInt("generation"))
    }

    @Test fun streamLimitStopsBeforeOversizedBytesAreWritten() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { copyRemoteFile(ByteArrayInputStream(ByteArray(65537)), output, 65536) }
        assertEquals(65536, output.size())
        assertEquals(100L * 1024 * 1024, REMOTE_FILE_LIMIT)
    }

    @Test fun interruptedReportDownloadDoesNotReplaceExistingDestination() = runBlocking {
        server().use { host ->
            val destination = File.createTempFile("existing-report", ".md")
            try {
                destination.writeText("previous valid result")
                host.enqueue(MockResponse().setBody("x".repeat(200000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                assertThrows(java.io.IOException::class.java) { runBlocking { FriendRemoteClient().exportTranscript(session(host), "rooms", "room", JSONObject().put("digest", "known"), destination) } }
                assertEquals("previous valid result", destination.readText())
            } finally { destination.delete() }
        }
    }

    @Test fun newRoomSelectsLeaderPolicyWithoutInventingALeader() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"room_id":"new-room","coordination_mode":"leader","leader_participant_id":null,"coordinator_model":null,"settings_revision":0}"""))
            val value = FriendRemoteClient().createRoom(session(host), "New room")
            assertNull(RemoteRoomSettings.parse(value).leader)
            val request = host.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertEquals("POST", request.method); assertEquals("/mobile/v2/rooms", request.path)
            assertEquals("leader", body.getString("coordination_mode"))
            assertFalse(body.has("leader_participant_id"))
        }
    }

    @Test fun humanOnlyPreflightNeedsNoCoordinatorAndFilesCanBeSentWithoutText() = runBlocking {
        server().use { host ->
            host.enqueue(MockResponse().setBody("""{"room_id":"room","ready":true,"coordination_mode":"leader","settings_revision":0,"coordinator":null,"participants":[]}"""))
            val value = FriendRemoteClient().roomPreflight(session(host), "room", emptyList())
            assertTrue(value.ready); assertNull(value.coordinator); assertTrue(value.checks.isEmpty())
            assertEquals("", remoteRoomMessage("", "file-only", listOf("material"), emptyList()).getString("text"))
            assertThrows(IllegalArgumentException::class.java) { remoteRoomMessage("", "empty", emptyList(), emptyList()) }
            assertEquals(32000, remoteRoomMessage("x".repeat(32000), "maximum", emptyList(), emptyList()).getString("text").length)
        }
    }
}
