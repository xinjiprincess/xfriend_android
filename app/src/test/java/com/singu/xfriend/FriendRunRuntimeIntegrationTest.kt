package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Real AgentRuntime + real task ledger/checkpoint adapter; only model HTTP is replaced. */
class FriendRunRuntimeIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private class State(override val workspaceRoot: File) : RuntimeState {
        override val experts = listOf(Expert("e", "E", "help"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override var runtimeHistorySteps = emptySet<String>()
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "NOT_A_REAL_KEY"
        override fun save() { }
    }
    private fun answer() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", "done"))))
    private fun write() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "write-call").put("type", "function").put("function", JSONObject().put("name", "write_file")
                .put("arguments", JSONObject().put("path", "proof.txt").put("content", "planned").toString())))))))
    private fun execution(runtime: AgentRuntime) = object : FriendRunExecution {
        override suspend fun preflight(resourceId: String, team: Boolean) = runtime.preflight(resourceId, team)
        override suspend fun run(resourceId: String, input: String, team: Boolean, onStatus: (String) -> Unit, onDelta: (String) -> Unit) =
            runtime.run(resourceId, input, onStatus, onDelta)
    }

    @Test fun explicitVerifyContinuesConfirmedWriteWithoutRewritingFileOrResendingModelToolCall() = runBlocking<Unit> {
        val state = State(temporary.newFolder()); val q = FriendRunQueue(FriendRunMemory(), Any())
        q.enqueue("e", "write", false, "req"); val r = q.claim()!!
        val adapter = FriendRunRuntimeCheckpoint(q, r.id) { true }; var failCommit = true; var requests = 0
        val interrupted = object : RuntimeCheckpoint {
            override suspend fun load() = adapter.load()
            override suspend fun save(checkpoint: JSONObject) {
                if (failCommit && checkpoint.getJSONObject("steps").optJSONObject("expert")?.optInt("tool_index") == 1) {
                    failCommit = false; throw IOException("simulated crash after file write before checkpoint commit")
                }
                adapter.save(checkpoint)
            }
        }
        val http: suspend (Request) -> JSONObject = { if (requests++ == 0) write() else answer() }
        FriendRunWorker(q, execution(AgentRuntime(state, checkpoint = interrupted, requestExecutor = http)), { true }).execute(r)
        assertEquals("requires_review", q.runs().single().state)
        assertFalse(q.runs().single().canResume); assertTrue(q.runs().single().canVerifyCheckpoint)
        val file = File(state.workspaceRoot, "e/proof.txt"); assertEquals("planned", file.readText())
        assertTrue(file.setLastModified(1_600_000_000_000L)); val stamp = file.lastModified()
        q.verifyAndResume(r.id); val next = q.claim()!!
        FriendRunWorker(q, execution(AgentRuntime(state, checkpoint = adapter, requestExecutor = http)), { true }).execute(next)
        assertEquals("completed", q.runs().single().state); assertEquals("done", q.runs().single().partial)
        assertEquals(stamp, file.lastModified()); assertEquals(2, requests)
        assertEquals(listOf(Message("user", "write"), Message("assistant", "done")), state.messages["e"]?.map { it.copy(id = "") })
        assertFalse(FriendRunCodec.encode(q.runs()).contains(state.apiKey))
    }

    @Test fun explicitVerifyWithMissingWriteNeverExecutesToolAndStaysRequiresReview() = runBlocking<Unit> {
        val state = State(temporary.newFolder()); val q = FriendRunQueue(FriendRunMemory(), Any())
        q.enqueue("e", "write", false, "req"); val r = q.claim()!!
        val adapter = FriendRunRuntimeCheckpoint(q, r.id) { true }; var interrupt = true; var requests = 0
        val beforeWrite = object : RuntimeCheckpoint {
            override suspend fun load() = adapter.load()
            override suspend fun save(checkpoint: JSONObject) {
                adapter.save(checkpoint)
                if (interrupt && checkpoint.getJSONObject("steps").optJSONObject("expert")?.has("pending") == true) {
                    interrupt = false; throw IOException("crash before file write")
                }
            }
        }
        val http: suspend (Request) -> JSONObject = { requests++; write() }
        FriendRunWorker(q, execution(AgentRuntime(state, checkpoint = beforeWrite, requestExecutor = http)), { true }).execute(r)
        val file = File(state.workspaceRoot, "e/proof.txt"); assertFalse(file.exists())
        assertTrue(q.runs().single().canVerifyCheckpoint)
        q.verifyAndResume(r.id)
        FriendRunWorker(q, execution(AgentRuntime(state, checkpoint = adapter, requestExecutor = http)), { true }).execute(q.claim()!!)
        assertEquals("requires_review", q.runs().single().state)
        assertFalse(file.exists()); assertEquals(1, requests); assertNull(q.claim())
        assertFalse(q.runs().single().canResume)
    }
}
