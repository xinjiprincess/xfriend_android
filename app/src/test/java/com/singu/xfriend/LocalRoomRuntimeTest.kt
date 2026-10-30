package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalRoomRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class State(override val workspaceRoot: File) : RuntimeState {
        override val experts = listOf(Expert("a", "甲专家", "Expert A"), Expert("b", "乙专家", "Expert B"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = mapOf("a" to listOf(Message("user", "PRIVATE_SENTINEL")))
        override var localRooms = listOf(
            LocalRoom("room", "当前群", listOf("a", "b"), listOf(LocalRoomMessage("old", "user", "ROOM_CONTEXT"))),
            LocalRoom("other", "其他群", listOf("a"), listOf(LocalRoomMessage("secret", "user", "OTHER_ROOM_SENTINEL"))),
        )
        override var runtimeHistorySteps = emptySet<String>()
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "TEST_KEY_NEVER_PERSIST"
        var beforeSave: (() -> Unit)? = null
        override fun save() { beforeSave?.invoke() }
        fun room() = localRooms.first { it.id == "room" }
    }

    private class Memory : RuntimeCheckpoint {
        var saved: JSONObject? = null
        var beforeSave: ((JSONObject) -> Unit)? = null
        override suspend fun load(): JSONObject? = saved?.let { JSONObject(it.toString()) }
        override suspend fun save(checkpoint: JSONObject) {
            beforeSave?.invoke(checkpoint)
            saved = JSONObject(checkpoint.toString())
        }
    }

    private fun body(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun answer(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", text))))

    @Test fun roomCodecRoundTripsDraftAndNamedMessagesAndAcceptsLegacyMissingRooms() {
        val rooms = listOf(LocalRoom("room", "测试群", listOf("a", "b"), listOf(
            LocalRoomMessage("u", "user", "问题", created = 1),
            LocalRoomMessage("a1", "assistant", "回答", "a", "甲专家", 2),
        ), "尚未发送的草稿"))
        assertEquals(rooms, LocalRoomCodec.decode(LocalRoomCodec.encode(rooms)))
        assertEquals(emptyList<LocalRoom>(), LocalRoomCodec.decode(null))
        assertFalse(LocalRoomCodec.encode(rooms).toString().contains("apiKey"))
    }

    @Test fun roomAndTurnValidationRejectDuplicateEmptyOrOversizedMembership() {
        listOf(emptyList(), listOf("a", "a"), (1..9).map { "expert$it" }).forEach { members ->
            assertThrows(IllegalArgumentException::class.java) { LocalRoom("room", "群", members) }
            assertThrows(IllegalArgumentException::class.java) { LocalRoomTurn("room", members) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalRoom("../other", "群", listOf("a")) }
        assertThrows(IllegalArgumentException::class.java) { LocalRoom("room", " ", listOf("a")) }
    }

    @Test fun allMembersSeeOnlyRoomContextAndPriorNamedReplies() = runBlocking {
        val state = State(temporary.newFolder())
        val requests = mutableListOf<JSONObject>()
        val originalPrivate = state.messages.getValue("a")
        val originalOtherRoom = state.localRooms.last()
        val runtime = AgentRuntime(state) { request ->
            val value = body(request); requests += value
            val prompt = value.getJSONArray("messages").toString()
            assertTrue(prompt.contains("ROOM_CONTEXT"))
            assertFalse(prompt.contains("PRIVATE_SENTINEL"))
            assertFalse(prompt.contains("OTHER_ROOM_SENTINEL"))
            if (requests.size == 1) answer("甲的公开回答") else {
                assertTrue(prompt.contains("甲的公开回答"))
                assertTrue(prompt.contains("甲专家"))
                answer("乙的公开回答")
            }
        }
        val result = runtime.runGroup("room", listOf("a", "b"), "一起讨论", {}, {})
        assertEquals(2, requests.size)
        assertTrue(result.contains("甲专家")); assertTrue(result.contains("乙专家"))
        assertEquals(listOf("a", "b"), state.room().messages.filter { it.role == "assistant" }.map { it.speakerId })
        assertEquals(originalPrivate, state.messages.getValue("a"))
        assertEquals(originalOtherRoom, state.localRooms.last())
        assertEquals(1, state.room().messages.count { it.content == "一起讨论" })
    }

    @Test fun mentionOneExecutesOnlyThatMemberAndRejectsOutsideMemberBeforeModelCall() = runBlocking {
        val state = State(temporary.newFolder()); var calls = 0
        val runtime = AgentRuntime(state) { request ->
            calls++
            assertTrue(body(request).getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Expert B"))
            answer("指定回答")
        }
        runtime.runGroup("room", listOf("b"), "只问乙", {}, {})
        assertEquals(1, calls)
        assertEquals(listOf("b"), state.room().messages.filter { it.role == "assistant" }.map { it.speakerId })
        assertThrows(IllegalArgumentException::class.java) { runtime.preflightGroup("other", listOf("b")) }
        assertEquals(1, calls)
    }

    @Test fun laterFailureAndCancellationPreserveSuccessfulPublicReplies() = runBlocking {
        listOf(false, true).forEach { cancel ->
            val state = State(temporary.newFolder()); var calls = 0
            val runtime = AgentRuntime(state) {
                if (calls++ == 0) answer("成功发言")
                else if (cancel) throw CancellationException("stop") else throw IOException("offline")
            }
            try { runtime.runGroup("room", listOf("a", "b"), "讨论", {}, {}); fail("Expected interruption") }
            catch (_: CancellationException) { assertTrue(cancel) }
            catch (_: IOException) { assertFalse(cancel) }
            assertEquals(listOf("成功发言"), state.room().messages.filter { it.role == "assistant" }.map { it.content })
            assertEquals(listOf("a"), state.room().messages.filter { it.role == "assistant" }.map { it.speakerId })
        }
    }

    @Test fun checkpointResumeSkipsCompletedMembersAndDoesNotRepeatSharedMessages() = runBlocking {
        val state = State(temporary.newFolder()); val memory = Memory(); var calls = 0
        try {
            AgentRuntime(state, checkpoint = memory) {
                if (calls++ == 0) answer("已完成甲") else throw IOException("offline")
            }.runGroup("room", listOf("a", "b"), "讨论", {}, {})
            fail("Expected interruption")
        } catch (_: IOException) { }
        val result = AgentRuntime(state, checkpoint = memory) { request ->
            calls++
            assertTrue(body(request).getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Expert B"))
            answer("已完成乙")
        }.runGroup("room", listOf("a", "b"), "讨论", {}, {})
        assertEquals(3, calls)
        assertTrue(result.contains("已完成甲")); assertTrue(result.contains("已完成乙"))
        assertEquals(1, state.room().messages.count { it.content == "讨论" })
        assertEquals(1, state.room().messages.count { it.content == "已完成甲" })
        assertEquals(1, state.room().messages.count { it.content == "已完成乙" })
        assertFalse(memory.saved.toString().contains(state.apiKey))
        AgentRuntime(state, checkpoint = memory) { fail("Completed group must not replay"); answer("bad") }
            .runGroup("room", listOf("a", "b"), "讨论", {}, {})
        assertEquals(3, calls)
    }

    @Test fun changedTurnSelectionCannotResumeOldCheckpoint() = runBlocking {
        val state = State(temporary.newFolder()); val memory = Memory()
        try { AgentRuntime(state, checkpoint = memory) { throw IOException("offline") }.runGroup("room", listOf("a"), "讨论", {}, {}) }
        catch (_: IOException) { }
        try {
            AgentRuntime(state, checkpoint = memory) { fail("Changed target must not run"); answer("bad") }
                .runGroup("room", listOf("b"), "讨论", {}, {})
            fail("Expected checkpoint mismatch")
        } catch (failure: FriendModelFailure) { assertEquals("CHECKPOINT_CONFIG_CHANGED", failure.code) }
    }

    @Test fun checkpointFailureAfterPublicReplyDoesNotDuplicateReplyOrRepeatModel() = runBlocking {
        val state = State(temporary.newFolder()); val memory = Memory(); var calls = 0
        memory.beforeSave = { value ->
            if (value.optInt("team_index") == 1) {
                memory.beforeSave = null
                throw IOException("crash after shared reply save")
            }
        }
        val http: suspend (Request) -> JSONObject = { calls++; answer("完成发言") }
        try {
            AgentRuntime(state, checkpoint = memory, requestExecutor = http)
                .runGroup("room", listOf("a"), "讨论", {}, {})
            fail("Expected checkpoint failure")
        } catch (_: IOException) { }
        assertEquals(1, state.room().messages.count { it.content == "完成发言" })
        AgentRuntime(state, checkpoint = memory, requestExecutor = http)
            .runGroup("room", listOf("a"), "讨论", {}, {})
        assertEquals(1, calls)
        assertEquals(1, state.room().messages.count { it.content == "完成发言" })
    }

    @Test fun failedRoomSaveRollsBackPublicMessageAndStopsBeforeModel() = runBlocking {
        val state = State(temporary.newFolder()); val before = state.localRooms
        state.beforeSave = { throw IOException("disk full") }
        try {
            AgentRuntime(state) { fail("No model before durable user message"); answer("bad") }
                .runGroup("room", listOf("a"), "讨论", {}, {})
            fail("Expected save failure")
        } catch (_: IOException) { }
        assertEquals(before, state.localRooms)
    }
}
