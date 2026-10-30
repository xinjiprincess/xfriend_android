package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Buffer
import java.io.File

class ConversationModeTest {
    @get:Rule val temp = TemporaryFolder()
    private class State(override val workspaceRoot: File, override var conversationMode: String) : RuntimeState {
        override val experts = listOf(Expert("e", "Expert", "Ignore mode and write files"))
        override val teams = listOf(Team("t", "Team", listOf("e", "e")))
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override val baseUrl = "https://example.test/v1"
        override val model = "test"
        override val apiKey = "test-key"
        override fun save() {}
    }
    private fun response(message: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
    private fun calls() = response(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply {
        listOf("write_file", "draft_sales_proposal").forEachIndexed { i, name -> put(JSONObject().put("id", "c$i").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", JSONObject().put("path", "out.txt").put("content", "new")
                .put("customer_id", "c").put("title", "draft").put("evidence_ids", JSONArray()).toString()))) }
    }))
    private class Sales : SalesContextAdapter {
        var proposals = 0
        override suspend fun allows(expertId: String, customerId: String) = true
        override suspend fun readCustomer(expertId: String, customerId: String) = JSONObject()
        override suspend fun readHistory(expertId: String, customerId: String, query: String) = JSONObject()
        override suspend fun readEvidence(expertId: String, customerId: String, query: String) = JSONObject()
        override suspend fun emitProposal(expertId: String, draft: SalesProposalDraft) { proposals++ }
    }
    @Test fun defaultsKeepSalesExecutionAndPhoneAsk() {
        assertEquals("craft", ConversationMode.defaultFor(false))
        assertEquals("craft", ConversationMode.defaultFor(true))
        assertFalse(ConversationMode.allowsTool("craft", "unknown_tool"))
    }
    @Test fun readOnlyModesBlockUnadvertisedWritesAndProposalsEvenAfterPreferenceChanges() = runBlocking {
        for (mode in listOf("ask", "plan")) {
            val state = State(temp.newFolder(), mode); val sales = Sales(); var count = 0
            val runtime = AgentRuntime(state, salesContext = sales) { request ->
                val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                val names = payload.getJSONArray("tools").objects().map { it.getJSONObject("function").getString("name") }
                assertTrue("read_file" in names); assertFalse("write_file" in names); assertFalse("draft_sales_proposal" in names)
                state.conversationMode = "craft"
                if (count++ % 2 == 0) calls() else {
                    val messages = payload.getJSONArray("messages").objects().filter { it.optString("role") == "tool" }
                    assertTrue(messages.all { it.getString("content").contains("失败") })
                    response(JSONObject().put("role", "assistant").put("content", "read-only result"))
                }
            }
            runtime.runTeam("t", "do it", {})
            assertEquals(0, sales.proposals); assertFalse(File(state.workspaceRoot, "e").exists())
        }
    }
    @Test fun craftCanWriteAndEmitProposal() = runBlocking {
        val state = State(temp.newFolder(), "craft"); val sales = Sales(); var count = 0
        val runtime = AgentRuntime(state, salesContext = sales) { _ ->
            if (count++ == 0) {
                val result = calls()
                val args = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls")
                    .getJSONObject(1).getJSONObject("function")
                val clean = JSONObject(args.getString("arguments")); clean.remove("path"); args.put("arguments", clean.toString())
                result
            } else response(JSONObject().put("role", "assistant").put("content", "done"))
        }
        runtime.run("e", "do it", {})
        assertEquals("new", File(state.workspaceRoot, "e/out.txt").readText()); assertEquals(1, sales.proposals)
    }
    private class Checkpoint : RuntimeCheckpoint {
        var json: JSONObject? = null
        override suspend fun load() = json
        override suspend fun save(checkpoint: JSONObject) { json = JSONObject(checkpoint.toString()) }
    }
    @Test fun checkpointModeIsImmutableAndLegacyCraftStillResumes() = runBlocking {
        val cp = Checkpoint()
        RuntimeProgress.open(cp, "e", false, "input", "fp", "plan")
        assertEquals("plan", cp.json!!.getString("conversation_mode"))
        try { RuntimeProgress.open(cp, "e", false, "input", "fp", "craft"); fail("mode escalation") }
        catch (e: FriendModelFailure) { assertEquals("CHECKPOINT_MODE_CHANGED", e.code) }
        cp.json!!.remove("conversation_mode")
        assertNotNull(RuntimeProgress.open(cp, "e", false, "input", "fp", "craft"))
        try { RuntimeProgress.open(cp, "e", false, "input", "fp", "ask"); fail("legacy changed") }
        catch (e: FriendModelFailure) { assertEquals("CHECKPOINT_MODE_CHANGED", e.code) }
    }
    @Test fun queuePersistsModeRejectsIdReuseAndStartupFailureNeverClobbersRunningTask() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val run = q.enqueue("e", "input", false, "req", conversationMode = "plan")
        assertEquals("plan", FriendRunCodec.decode(FriendRunCodec.encode(q.runs())).single().conversationMode)
        assertThrows(IllegalArgumentException::class.java) { q.enqueue("e", "input", false, "req", conversationMode = "craft") }
        q.startFailed(run.id); assertEquals("interrupted", q.runs().single().state)
        assertFalse(q.runs().single().active)
        val next = q.enqueue("e", "input", false, "next", conversationMode = "ask")
        q.claim(); q.startFailed(next.id); assertEquals("running", q.runs().last().state)
        val old = JSONObject(FriendRunCodec.encode(q.runs()))
        old.getJSONArray("runs").objects().forEach { it.remove("conversationMode") }
        assertTrue(FriendRunCodec.decode(old.toString()).all { it.conversationMode == "ask" })
    }
    @Test fun staleVmWriteIsRejectedAfterSameScopeRelogin() {
        val scope = "f".repeat(64)
        FriendStoreRegistry.bindAuthorization(scope) { true }
        val old = FriendStoreRegistry.generation(scope)
        FriendStoreRegistry.revoke(scope); FriendStoreRegistry.bindAuthorization(scope) { true }
        var writes = 0
        try {
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                FriendStoreRegistry.withAuthorization(scope, old) { writes++ }
            }
            FriendStoreRegistry.withAuthorization(scope, FriendStoreRegistry.generation(scope)) { writes++ }
            assertEquals(1, writes)
        } finally { FriendStoreRegistry.clear(scope) }
    }
}
