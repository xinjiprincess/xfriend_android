package com.singu.xfriend

import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendProviderWireTest {
    private fun connection(format: String, type: String = "api", base: String = when (format) {
        "anthropic_messages" -> "https://api.anthropic.com/v1"
        "gemini_generate" -> "https://generativelanguage.googleapis.com/v1beta"
        else -> "https://models.example.test/v1"
    }) = FriendModelConnection(id = "connection-one", providerType = type, apiFormat = format,
        baseUrl = base, model = "test-model", credentials = mapOf("api_key" to "test-api-key"))
    private fun canonical() = JSONObject().put("model", "ignored-canonical-model").put("stream", true).put("max_tokens", 512)
        .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "系统指令"))
            .put(JSONObject().put("role", "user").put("content", "你好")))
    private fun body(prepared: ProviderWireRequest): JSONObject {
        val buffer = Buffer(); prepared.request.body!!.writeTo(buffer); return JSONObject(buffer.readUtf8())
    }
    private fun message(result: JSONObject): JSONObject = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    private fun tool(id: String = "call-1", name: String = "read_file", args: String = "{\"path\":\"notes.txt\"}") = JSONObject()
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args))
    private fun toolDefinition() = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "read_file").put("description", "读取当前工作区文件")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("path", JSONObject().put("type", "string")))))
    private fun assertJson(expected: Any, actual: Any) { assertEquals(runtimeCanonical(expected), runtimeCanonical(actual)) }

    @Test fun theFourWireFormatsUseTheirOwnEndpointAuthenticationAndBodyShape() {
        val chat = FriendProviderWire.prepare(connection("openai_chat"), canonical(), "session")
        assertEquals("https://models.example.test/v1/chat/completions", chat.request.url.toString())
        assertEquals("Bearer test-api-key", chat.request.header("Authorization"))
        assertEquals("test-model", body(chat).getString("model")); assertTrue(body(chat).has("messages"))

        val responses = FriendProviderWire.prepare(connection("openai_responses"), canonical(), "session")
        assertEquals("https://models.example.test/v1/responses", responses.request.url.toString())
        assertEquals("Bearer test-api-key", responses.request.header("Authorization"))
        assertFalse(body(responses).has("messages")); assertEquals("系统指令", body(responses).getString("instructions"))
        assertEquals(512, body(responses).getInt("max_output_tokens")); assertFalse(body(responses).getBoolean("store"))

        val anthropic = FriendProviderWire.prepare(connection("anthropic_messages"), canonical(), "session")
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.request.url.toString())
        assertNull(anthropic.request.header("Authorization")); assertEquals("test-api-key", anthropic.request.header("x-api-key"))
        assertEquals("2023-06-01", anthropic.request.header("anthropic-version"))
        assertEquals("系统指令", body(anthropic).getString("system")); assertEquals(1, body(anthropic).getJSONArray("messages").length())

        val gemini = FriendProviderWire.prepare(connection("gemini_generate"), canonical(), "session")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/test-model:streamGenerateContent?alt=sse", gemini.request.url.toString())
        assertNull(gemini.request.header("Authorization")); assertEquals("test-api-key", gemini.request.header("x-goog-api-key"))
        assertEquals("系统指令", body(gemini).getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(512, body(gemini).getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertTrue(body(gemini).has("contents")); assertFalse(body(gemini).has("messages"))
    }

    @Test fun completedEndpointInputsDoNotProduceDuplicatePathSuffixes() {
        listOf("openai_chat" to "/chat/completions", "openai_responses" to "/responses", "anthropic_messages" to "/messages").forEach { (format, suffix) ->
            val base = "https://models.example.test/v1"
            val prepared = FriendProviderWire.prepare(connection(format, base = base + suffix + "/"), canonical(), "session")
            assertEquals(base + suffix, prepared.request.url.toString())
        }
    }

    @Test fun openCodeIncludesAStableSessionForBothNamedPresetsAndCustomSubdomains() {
        listOf(connection("openai_chat", base = "https://opencode.ai/zen/v1").copy(providerId = "opencode-zen-openai"),
            connection("anthropic_messages", base = "https://custom.opencode.ai/zen").copy(providerId = "custom")).forEach { value ->
            val prepared = FriendProviderWire.prepare(value, canonical(), "stable-conversation-id")
            assertEquals("stable-conversation-id", prepared.request.header("x-opencode-session"))
        }
        val random = FriendProviderWire.prepare(connection("openai_chat", base = "https://opencode.ai/zen/v1"), canonical(), "")
        assertTrue(random.request.header("x-opencode-session").orEmpty().isNotBlank())
    }

    @Test fun customHeadersCannotReplaceTheSelectedConnectionsAuthenticationOrHost() {
        val extras = JSONObject().put("Authorization", "Bearer not-this-token").put("x-api-key", "not-this-key")
            .put("Host", "attacker.example.test").put("Cookie", "private-cookie").put("Content-Length", "1")
            .put("X-Trace-Label", "safe-custom-value")
        listOf("openai_chat", "anthropic_messages", "gemini_generate").forEach { format ->
            val value = connection(format).copy(credentials = mapOf("api_key" to "test-api-key", "headers" to extras.toString()))
            val request = FriendProviderWire.prepare(value, canonical(), "session").request
            assertEquals("safe-custom-value", request.header("X-Trace-Label"))
            assertNull(request.header("Host")); assertNull(request.header("Cookie")); assertNull(request.header("Content-Length"))
            assertFalse(request.headers.toString().contains("not-this-token")); assertFalse(request.headers.toString().contains("not-this-key"))
        }
    }

    @Test fun arkPlansStayOnTheirExactPlanOriginAndUseBearerAuthForEveryProtocol() {
        for ((plan, lane) in listOf("agent" to "plan", "coding" to "coding")) for (format in listOf("openai_chat", "openai_responses", "anthropic_messages")) {
            val base = "https://ark.cn-beijing.volces.com/api/$lane" + if (format == "anthropic_messages") "" else "/v3"
            val value = connection(format, "volcengine_ark", base).copy(metadata = mapOf("plan_type" to plan))
            val prepared = FriendProviderWire.prepare(value, canonical(), "session")
            val suffix = when (format) { "openai_chat" -> "/chat/completions"; "openai_responses" -> "/responses"; else -> "/v1/messages" }
            assertEquals(base + suffix, prepared.request.url.toString())
            assertEquals("Bearer test-api-key", prepared.request.header("Authorization")); assertNull(prepared.request.header("x-api-key"))
            assertFalse(prepared.request.url.encodedPath.startsWith("/api/v3/"))
            val mismatch = value.copy(baseUrl = "https://ark.cn-beijing.volces.com/api/v3")
            assertEquals("MODEL_ARK_ENDPOINT_MISMATCH", assertThrows(FriendModelFailure::class.java) {
                FriendProviderWire.prepare(mismatch, canonical(), "session")
            }.code)
        }
    }

    @Test fun codexResponsesUsesItsAccountBindingAndOmitsUnsupportedGenerationControls() {
        val value = connection("openai_responses", "codex", "https://chatgpt.com/backend-api/codex")
            .copy(credentials = mapOf("access_token" to "oauth-access", "account_id" to "bound-account"))
        val canonical = canonical().put("temperature", 0.4).put("top_p", 0.8)
        val prepared = FriendProviderWire.prepare(value, canonical, "stable-session")
        assertEquals("https://chatgpt.com/backend-api/codex/responses", prepared.request.url.toString())
        assertEquals("Bearer oauth-access", prepared.request.header("Authorization"))
        assertEquals("bound-account", prepared.request.header("chatgpt-account-id"))
        assertEquals("stable-session", prepared.request.header("session-id"))
        assertEquals("stable-session", body(prepared).getString("prompt_cache_key"))
        assertFalse(body(prepared).has("max_output_tokens")); assertFalse(body(prepared).has("max_tokens"))
        assertFalse(body(prepared).has("temperature")); assertFalse(body(prepared).has("top_p"))
        assertEquals("MODEL_AUTH_REQUIRED", assertThrows(FriendModelFailure::class.java) {
            FriendProviderWire.prepare(value.copy(credentials = mapOf("access_token" to "oauth-access")), canonical, "session")
        }.code)
        assertTrue(canonical.has("max_tokens")); assertTrue(canonical.has("temperature"))
    }

    @Test fun nativeOAuthTokensCannotBeSentToCustomOriginsOrChatCompletions() {
        listOf(connection("openai_responses", "codex", "https://chatgpt.com/backend-api/codex")
            .copy(credentials = mapOf("access_token" to "oauth-access", "account_id" to "account")),
            connection("openai_responses", "xai_oauth", "https://api.x.ai/v1").copy(credentials = mapOf("access_token" to "oauth-access"))).forEach { value ->
            assertThrows(FriendModelFailure::class.java) { FriendProviderWire.prepare(value.copy(baseUrl = "https://other-provider.example.test/v1"), canonical(), "session") }
            assertThrows(FriendModelFailure::class.java) { FriendProviderWire.prepare(value.copy(apiFormat = "openai_chat"), canonical(), "session") }
        }
    }

    @Test fun responsesToolHistoryReplaysOpaqueReasoningOnlyForTheSameConnection() {
        val value = connection("openai_responses")
        val output = JSONArray().put(JSONObject().put("type", "reasoning").put("id", "reasoning-1").put("encrypted_content", "opaque-reasoning-evidence").put("summary", JSONArray()))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "需要读取文件"))))
            .put(JSONObject().put("type", "function_call").put("id", "provider-item-1").put("call_id", "call-1").put("name", "read_file").put("arguments", "{\"path\":\"notes.txt\"}"))
        val assistant = message(providerResult(value, JSONObject().put("status", "completed").put("output", output)))
        val canonical = canonical().put("tools", JSONArray().put(toolDefinition()))
        canonical.getJSONArray("messages").put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "已读取内容"))
        val prepared = body(FriendProviderWire.prepare(value, canonical, "session"))
        val items = prepared.getJSONArray("input").objects()
        assertJson(output.getJSONObject(0), items.first { it.optString("type") == "reasoning" })
        assertEquals(1, items.count { it.optString("type") == "function_call" })
        assertEquals("call-1", items.last().getString("call_id")); assertEquals("已读取内容", items.last().getString("output"))
        assertEquals("function", prepared.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertFalse(prepared.getJSONArray("tools").getJSONObject(0).has("function"))
        assertTrue(prepared.getJSONArray("include").strings().contains("reasoning.encrypted_content"))
        val changed = body(FriendProviderWire.prepare(value.copy(id = "another-connection"), canonical, "session"))
        assertFalse(changed.toString().contains("opaque-reasoning-evidence"))
        assertEquals(1, changed.getJSONArray("input").objects().count { it.optString("type") == "function_call" })
    }

    @Test fun anthropicSignedThinkingAndToolResultsSurviveTheRoundTripWithoutBecomingVisibleText() {
        val value = connection("anthropic_messages")
        val blocks = JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "内部推理").put("signature", "signed-thinking-evidence"))
            .put(JSONObject().put("type", "redacted_thinking").put("data", "opaque-redacted-evidence"))
            .put(JSONObject().put("type", "text").put("text", "需要读取文件"))
            .put(JSONObject().put("type", "tool_use").put("id", "call-1").put("name", "read_file").put("input", JSONObject().put("path", "notes.txt")))
        val assistant = message(providerResult(value, JSONObject().put("stop_reason", "tool_use").put("content", blocks)))
        assertEquals("需要读取文件", assistant.getString("content"))
        val canonical = canonical().put("tools", JSONArray().put(toolDefinition()))
            .put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_file")))
        canonical.getJSONArray("messages").put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "文件内容"))
        val wire = body(FriendProviderWire.prepare(value, canonical, "session"))
        val turns = wire.getJSONArray("messages").objects()
        assertJson(blocks, turns.first { it.getString("role") == "assistant" }.getJSONArray("content"))
        val resultBlock = turns.last().getJSONArray("content").getJSONObject(0)
        assertEquals("user", turns.last().getString("role")); assertEquals("tool_result", resultBlock.getString("type"))
        assertEquals("call-1", resultBlock.getString("tool_use_id")); assertEquals("文件内容", resultBlock.getString("content"))
        assertEquals("tool", wire.getJSONObject("tool_choice").getString("type"))
        assertTrue(wire.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        val changed = body(FriendProviderWire.prepare(value.copy(id = "another-connection"), canonical, "session"))
        assertFalse(changed.toString().contains("signed-thinking-evidence")); assertFalse(changed.toString().contains("内部推理"))
    }

    @Test fun geminiThoughtSignaturesAndNativeToolIdsSurviveOnlyWithinTheirConnection() {
        val value = connection("gemini_generate")
        val parts = JSONArray().put(JSONObject().put("text", "内部思考").put("thought", true).put("thoughtSignature", "thought-signature"))
            .put(JSONObject().put("text", "先读取文件"))
            .put(JSONObject().put("functionCall", JSONObject().put("id", "gemini-call").put("name", "read_file").put("args", JSONObject().put("path", "notes.txt"))).put("thoughtSignature", "tool-signature"))
        val assistant = message(providerResult(value, JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("finishReason", "STOP").put("content", JSONObject().put("role", "model").put("parts", parts))))))
        assertEquals("先读取文件", assistant.getString("content"))
        val canonical = canonical().put("tools", JSONArray().put(toolDefinition()))
        canonical.getJSONArray("messages").put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "gemini-call").put("content", "文件内容"))
        val wire = body(FriendProviderWire.prepare(value, canonical, "session"))
        val contents = wire.getJSONArray("contents").objects()
        assertJson(parts, contents.first { it.getString("role") == "model" }.getJSONArray("parts"))
        val result = contents.last().getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("gemini-call", result.getString("id")); assertEquals("read_file", result.getString("name"))
        assertEquals("文件内容", result.getJSONObject("response").getString("result"))
        val changed = body(FriendProviderWire.prepare(value.copy(id = "another-connection"), canonical, "session"))
        assertFalse(changed.toString().contains("thought-signature")); assertFalse(changed.toString().contains("tool-signature"))
        assertFalse(changed.getJSONArray("contents").objects().last().getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").has("id"))
    }

    @Test fun unmatchedGeminiToolResultsAreRejectedInsteadOfInventingAFunctionName() {
        val canonical = canonical()
        canonical.getJSONArray("messages").put(JSONObject().put("role", "tool").put("tool_call_id", "missing-call").put("content", "untrusted result"))
        assertEquals("MODEL_TOOL_HISTORY_INVALID", assertThrows(FriendModelFailure::class.java) {
            FriendProviderWire.prepare(connection("gemini_generate"), canonical, "session")
        }.code)
    }

    @Test fun preparingAnyProtocolDoesNotMutateTheCanonicalCheckpointConversation() {
        for (format in friendProviderWireFormats) {
            val canonical = canonical().put("tools", JSONArray().put(toolDefinition()))
            canonical.getJSONArray("messages").put(JSONObject().put("role", "assistant").put("content", "先读文件")
                .put("native_connection_id", "different-connection").put("native_response_items", JSONArray())
                .put("tool_calls", JSONArray().put(tool())))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "读取结果"))
            val before = runtimeCanonical(canonical)
            FriendProviderWire.prepare(connection(format), canonical, "session")
            assertEquals(before, runtimeCanonical(canonical))
        }
    }

    @Test fun subscriptionAccountSpecializationStillReceivesTheProtocolConvertedBody() {
        val workbuddy = providerNewConnection("workbuddy_global", mapOf("access_token" to "workbuddy-token", "uid" to "uid", "domain" to "tenant"))
            .copy(model = "test-model", baseUrl = "https://other-origin.example.test")
        val workRequest = FriendProviderWire.prepare(workbuddy, canonical(), "session")
        assertEquals("https://www.workbuddy.ai/v2/chat/completions", workRequest.request.url.toString())
        assertEquals("Bearer workbuddy-token", workRequest.request.header("Authorization"))
        assertEquals("test-model", body(workRequest).getString("model"))

        val copilot = providerNewConnection("github_copilot", mapOf("access_token" to "copilot-token", "api_endpoint" to "https://api.business.githubcopilot.com"))
            .copy(model = "test-model", apiFormat = "openai_responses")
        val copilotRequest = FriendProviderWire.prepare(copilot, canonical(), "session")
        assertEquals("https://api.business.githubcopilot.com/responses", copilotRequest.request.url.toString())
        assertTrue(body(copilotRequest).has("input")); assertFalse(body(copilotRequest).has("messages"))
        assertEquals("vscode-chat", copilotRequest.request.header("Copilot-Integration-Id"))
    }

    @Test fun disabledQuotaOnlyAndEmbeddingSourcesCannotBePreparedForChat() {
        listOf(connection("openai_chat").copy(enabled = false), connection("openai_chat", "onnx"),
            connection("anthropic_messages", "claude_subscription"), connection("gemini_generate", "gemini_subscription")).forEach { value ->
            assertThrows(FriendModelFailure::class.java) { FriendProviderWire.prepare(value, canonical(), "session") }
        }
    }
    @Test fun officialOpenAiChatUsesCompletionBudgetWhileCompatibleEndpointsKeepTheirOwnBudget() {
        listOf("https://api.openai.com/v1" to "max_completion_tokens", "https://gateway.invalid/v1" to "max_tokens").forEach { (base, expected) ->
            val c = FriendModelConnection(id="budget", name="budget", apiFormat="openai_chat", baseUrl=base,
                model="o3", credentials=mapOf("api_key" to "test-key"))
            val canonical = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi"))).put("max_tokens", 2048)
            val request = FriendProviderWire.prepare(c, canonical, "session").request
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val wire = JSONObject(buffer.readUtf8())
            assertEquals(2048, wire.getInt(expected))
            assertFalse(wire.has(if (expected == "max_tokens") "max_completion_tokens" else "max_tokens"))
            assertEquals(2048, canonical.getInt("max_tokens"))
        }
    }

}
