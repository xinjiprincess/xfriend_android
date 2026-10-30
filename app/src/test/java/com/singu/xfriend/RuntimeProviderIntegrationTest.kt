package com.singu.xfriend

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

class RuntimeProviderIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private class State(override val workspaceRoot: File, override val modelConnections: List<FriendModelConnection>,
                        override val activeModelConnectionId: String) : RuntimeState {
        override val experts = listOf(Expert("e", "E", "help"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override val baseUrl = "https://legacy.invalid/v1"
        override val model = "legacy-model"
        override val apiKey = "legacy-key"
        override fun save() {}
    }
    private fun connection() = FriendModelConnection(id="selected", name="Selected", apiFormat="openai_responses",
        baseUrl="https://selected.invalid/v1", model="selected-model", credentials=mapOf("api_key" to "old-key"))
    private fun answer() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", "confirmed"))))
    private fun body(request: Request): JSONObject = Buffer().let { request.body!!.writeTo(it); JSONObject(it.readUtf8()) }

    @Test fun selectedNativeConnectionUsesFreshCredentialAndRealWireWithNoLegacyFallback() = runBlocking {
        val selected = connection(); val state = State(temporary.newFolder(), listOf(selected), selected.id)
        var refreshes = 0; var calls = 0
        val runtime = AgentRuntime(state) { request ->
            calls++
            assertEquals("https://selected.invalid/v1/responses", request.url.toString())
            assertEquals("Bearer rotated-key", request.header("Authorization"))
            val doc = body(request)
            assertEquals("selected-model", doc.getString("model"))
            assertTrue(doc.has("input")); assertFalse(doc.has("messages")); assertFalse(doc.toString().contains("legacy-key"))
            answer()
        }
        runtime.resolveProviderConnection = { c -> refreshes++; c.copy(credentials=mapOf("api_key" to "rotated-key")) }
        assertEquals("confirmed", runtime.run("e", "hello") {})
        assertEquals(1, refreshes); assertEquals(1, calls)
        assertFalse(state.messages.toString().contains("rotated-key"))
    }

    @Test fun removedOrDisabledSelectedConnectionFailsBeforeModelRequest() = runBlocking {
        listOf(emptyList(), listOf(connection().copy(enabled=false))).forEach { connections ->
            var calls = 0; val runtime = AgentRuntime(State(temporary.newFolder(), connections, "selected")) { calls++; answer() }
            try { runtime.run("e", "hello") {}; fail("Missing selected connection silently fell back") }
            catch (failure: FriendModelFailure) { assertEquals("CONFIG_PROVIDER", failure.code) }
            assertEquals(0, calls)
        }
    }

    @Test fun quotaOnlySubscriptionCannotFallBackToLegacyKey() = runBlocking {
        val selected = connection().copy(providerType="claude_subscription")
        var calls = 0; val runtime = AgentRuntime(State(temporary.newFolder(), listOf(selected), selected.id)) { calls++; answer() }
        try { runtime.run("e", "hello") {}; fail("Quota account accepted as inference") }
        catch (failure: FriendModelFailure) { assertEquals("MODEL_SUBSCRIPTION_QUOTA_ONLY", failure.code) }
        assertEquals(0, calls)
    }
}
