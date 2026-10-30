package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalRoomRunIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private class State(override val workspaceRoot: File) : RuntimeState {
        override val experts = listOf(Expert("a", "甲", "help"), Expert("b", "乙", "help"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override var runtimeHistorySteps = emptySet<String>()
        override var localRooms = listOf(LocalRoom("room", "讨论", listOf("a", "b")))
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "TEST_KEY_NEVER_PERSIST"
        override fun save() { }
    }
    private fun answer(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", text))))

    @Test fun queueWorkerAndRealRuntimeRecoverGroupWithoutRepeatingCompletedMember() = runBlocking {
        val state = State(temporary.newFolder())
        val queue = FriendRunQueue(FriendRunMemory(), Any())
        val group = LocalRoomTurn("room", listOf("a", "b"))
        val original = queue.enqueue("room", "讨论下一步", false, "request", localRoomTurn = group)
        val run = queue.claim()!!
        val checkpoint = FriendRunRuntimeCheckpoint(queue, run.id) { true }
        var calls = 0
        val runtime = AgentRuntime(state, checkpoint = checkpoint, requestExecutor = {
            when (++calls) { 1 -> answer("第一位完成"); 2 -> throw IOException("model disconnected"); else -> answer("第二位完成") }
        })
        val execution = AgentRunExecution(runtime, run.localRoomTurn)
        FriendRunWorker(queue, execution, { true }).execute(run)
        val failed = queue.runs().single()
        assertEquals("failed", failed.state)
        assertTrue(failed.canResume)
        assertEquals(listOf("a"), state.localRooms.single().messages.filter { it.role == "assistant" }.map { it.speakerId })
        queue.resume(original.id)
        FriendRunWorker(queue, execution, { true }).execute(queue.claim()!!)
        assertEquals("completed", queue.runs().single().state)
        assertEquals(3, calls)
        assertEquals(listOf("a", "b"), state.localRooms.single().messages.filter { it.role == "assistant" }.map { it.speakerId })
        assertEquals(1, state.localRooms.single().messages.count { it.role == "user" })
        assertTrue(state.messages.isEmpty())
        assertFalse(FriendRunCodec.encode(queue.runs()).contains(state.apiKey))
        assertFalse(LocalRoomCodec.encode(state.localRooms).toString().contains(state.apiKey))
    }

    @Test fun normalExpertStillUsesOriginalExecutionPath() = runBlocking {
        val state = State(temporary.newFolder())
        val queue = FriendRunQueue(FriendRunMemory(), Any())
        queue.enqueue("a", "私聊", false, "private")
        val runtime = AgentRuntime(state, requestExecutor = { answer("原生回答") })
        FriendRunWorker(queue, AgentRunExecution(runtime), { true }).execute(queue.claim()!!)
        assertEquals("completed", queue.runs().single().state)
        assertEquals(listOf(Message("user", "私聊"), Message("assistant", "原生回答")), state.messages["a"]?.map { it.copy(id = "") })
        assertTrue(state.localRooms.single().messages.isEmpty())
    }
}
