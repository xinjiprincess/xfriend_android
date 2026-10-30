package com.singu.xfriend

import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendProviderAccountTransportTest {
    private fun json(request: okhttp3.Request): JSONObject {
        val buffer = Buffer(); request.body!!.writeTo(buffer); return JSONObject(buffer.readUtf8())
    }

    @Test fun workbuddyUsesPinnedOriginIdentityHeadersAndItsChatToolChoiceDialect() {
        val connection = providerNewConnection("workbuddy_global", mapOf("access_token" to "private-access", "uid" to "uid", "domain" to "tenant"))
            .copy(baseUrl = "https://attacker.test")
        val body = JSONObject().put("model", "model").put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
            .put("tools", JSONArray()).put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_file")))
        val request = FriendProviderAccountTransport.prepare(connection, body, "session")!!
        assertEquals("https://www.workbuddy.ai/v2/chat/completions", request.url.toString())
        assertEquals("uid", request.header("X-User-Id"))
        assertEquals("1", request.header("X-No-Enterprise-Id"))
        assertEquals("tenant", request.header("X-Domain"))
        assertEquals("read_file", json(request).getString("tool_choice"))
        assertEquals("system", json(request).getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals(1, body.getJSONArray("messages").length())
        assertTrue(body.get("tool_choice") is JSONObject)
    }

    @Test fun copilotUsesThePinnedExchangedEndpointAndRequiredEditorHeaders() {
        val connection = providerNewConnection("github_copilot", mapOf("access_token" to "copilot-access", "api_endpoint" to "https://api.business.githubcopilot.com"))
        val request = FriendProviderAccountTransport.prepare(connection, JSONObject().put("model", "model"), "session")!!
        assertEquals("https://api.business.githubcopilot.com/chat/completions", request.url.toString())
        assertEquals("Bearer copilot-access", request.header("Authorization"))
        assertEquals("vscode-chat", request.header("Copilot-Integration-Id"))
        assertEquals("conversation-agent", request.header("OpenAI-Intent"))
    }

    @Test fun geminiPoolWrapsOnlyTheSelectedAccountAndReplaysThoughtSignaturesWithoutMutatingTheCanonicalBody() {
        val accounts = JSONArray().put(JSONObject().put("id", "0").put("enabled", true).put("access_token", "first-token").put("project_id", "first-project").put("model_ids", JSONArray(listOf("model"))))
            .put(JSONObject().put("id", "1").put("enabled", true).put("access_token", "second-token").put("project_id", "second-project").put("model_ids", JSONArray(listOf("model"))))
        val connection = providerNewConnection("gemini_pool", mapOf("accounts_json" to accounts.toString(), "pool_mode" to "manual", "active_account_id" to "1")).copy(model = "model", baseUrl = "https://attacker.test")
        val converted = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "model").put("parts", JSONArray()
            .put(JSONObject().put("thoughtSignature", "private-signature").put("functionCall", JSONObject().put("name", "read_file"))))))
        val request = FriendProviderAccountTransport.prepare(connection, converted, "stable-session")!!
        val body = json(request)
        assertEquals("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", request.url.toString())
        assertEquals("Bearer second-token", request.header("Authorization"))
        assertEquals("second-project", body.getString("project"))
        assertEquals("stable-session", body.getJSONObject("request").getString("sessionId"))
        val part = body.getJSONObject("request").getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("private-signature", part.getString("thought_signature"))
        assertFalse(part.has("thoughtSignature"))
        assertTrue(converted.toString().contains("thoughtSignature"))
        val wrapped = JSONObject().put("response", JSONObject().put("candidates", JSONArray()))
        assertTrue(FriendProviderAccountTransport.unwrap(connection, wrapped).has("candidates"))
    }

    @Test fun automaticPoolSelectionUsesKnownFractionsAndManualModeNeverSilentlyChangesAccounts() {
        val accounts = JSONArray().put(JSONObject().put("id", "0").put("enabled", true).put("access_token", "low-token").put("project_id", "low")
            .put("model_ids", JSONArray(listOf("model"))).put("model_quota", JSONObject().put("model", 0.1)))
            .put(JSONObject().put("id", "1").put("enabled", true).put("access_token", "high-token").put("project_id", "high")
                .put("model_ids", JSONArray(listOf("model"))).put("model_quota", JSONObject().put("model", 0.9)))
        val base = providerNewConnection("gemini_pool", mapOf("accounts_json" to accounts.toString(), "pool_mode" to "auto", "active_account_id" to "0")).copy(model = "model")
        val body = JSONObject().put("contents", JSONArray())
        assertEquals("Bearer high-token", FriendProviderAccountTransport.prepare(base, body, "")!!.header("Authorization"))
        val manual = base.copy(credentials = base.credentials + ("pool_mode" to "manual"))
        assertEquals("Bearer low-token", FriendProviderAccountTransport.prepare(manual, body, "")!!.header("Authorization"))
    }
}
