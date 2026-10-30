package com.singu.xfriend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Receives protocol-converted bodies from FriendProviderWire; authentication stays connection-scoped. */
object FriendProviderAccountTransport {
    fun prepare(connection: FriendModelConnection, convertedBody: JSONObject, sessionId: String): Request? {
        if (!connection.enabled) providerFail("MODEL_CONNECTION_DISABLED", "该模型连接已停用")
        if (connection.providerType in setOf("claude_subscription", "gemini_subscription"))
            providerFail("MODEL_SUBSCRIPTION_QUOTA_ONLY", "该订阅目前仅支持查询额度，不能用于聊天")
        val body = JSONObject(convertedBody.toString())
        val headers = mutableMapOf("Accept" to "text/event-stream")
        val endpoint = when (connection.providerType) {
            "workbuddy_cn", "workbuddy_global" -> {
                headers["Authorization"] = "Bearer ${providerRequired(connection.credentials["access_token"].orEmpty())}"
                headers.putAll(providerWorkbuddyHeaders(connection))
                listOf("ID", "Request-ID", "Message-ID").forEach { headers["X-Conversation-$it"] = UUID.randomUUID().toString() }
                val rows = body.optJSONArray("messages") ?: providerFail("MODEL_REQUEST_REJECTED", "WorkBuddy 消息格式无效")
                val messages = rows.providerObjects().map { JSONObject(it.toString()).apply {
                    if (optString("role") == "developer") put("role", "system")
                } }.toMutableList()
                if (connection.providerType == "workbuddy_global" && messages.firstOrNull()?.optString("role") != "system")
                    messages.add(0, JSONObject().put("role", "system").put("content", "You are a helpful assistant."))
                body.put("messages", JSONArray(messages)).put("stream", true)
                val choice = body.optJSONObject("tool_choice")
                if (choice != null) body.put("tool_choice", if (choice.optString("type") == "function") choice.optJSONObject("function")?.optString("name", "auto") ?: "auto" else choice.optString("type", "auto"))
                if (body.optString("tool_choice") == "none") { body.remove("tools"); body.remove("tool_choice") }
                if (body.optString("reasoning_effort") == "off") body.remove("reasoning_effort")
                providerBase(connection.providerType) + "/chat/completions"
            }
            "github_copilot" -> {
                headers["Authorization"] = "Bearer ${providerRequired(connection.credentials["access_token"].orEmpty())}"
                headers.putAll(providerCopilotHeaders)
                headers.putAll(mapOf("OpenAI-Intent" to "conversation-agent", "X-Initiator" to "user",
                    "X-Interaction-Type" to "conversation-agent", "X-Request-Id" to UUID.randomUUID().toString()))
                providerCopilotEndpoint(connection) + if (connection.apiFormat == "openai_responses") "/responses" else "/chat/completions"
            }
            "gemini_pool" -> {
                val mode = connection.credentials["pool_mode"].orEmpty().ifBlank { "manual" }
                val active = connection.credentials["active_account_id"].orEmpty()
                if (mode !in setOf("manual", "auto", "primarybackup")) providerFail("MODEL_CONFIGURATION_INVALID", "Gemini 账号池模式无效")
                val candidates = providerPoolAccounts(connection).filter { it.optBoolean("enabled", true) }
                    .filter { mode != "manual" || it.optString("id") == active }
                    .filter { account -> account.optJSONArray("model_ids")?.let { ids ->
                        (0 until ids.length()).any { ids.optString(it) == connection.model }
                    } ?: false }
                    .sortedWith(if (mode == "auto") compareByDescending<JSONObject> {
                        it.optJSONObject("model_quota")?.optDouble(connection.model, -1.0) ?: -1.0
                    }.thenBy { it.optString("id") != active }
                    else compareBy<JSONObject> { it.optString("id") != active }.thenBy { !it.optBoolean("primary") })
                val account = candidates.firstOrNull() ?: providerFail("MODEL_UNAVAILABLE", "选中的 Gemini 账号没有返回该模型的使用权限")
                val project = providerRequired(account.optString("project_id"))
                headers.putAll(providerPoolHeaders(providerRequired(account.optString("access_token"))))
                body.optJSONArray("contents").providerObjects().forEach { content ->
                    content.optJSONArray("parts").providerObjects().forEach { part ->
                        if (part.has("thoughtSignature")) part.put("thought_signature", part.remove("thoughtSignature"))
                    }
                }
                if (sessionId.isNotBlank()) body.put("sessionId", sessionId)
                val wrapped = JSONObject().put("project", project).put("model", connection.model).put("request", body)
                    .put("requestType", "agent").put("userAgent", "antigravity").put("requestId", UUID.randomUUID().toString())
                return request("$PROVIDER_POOL_BASE/v1internal:streamGenerateContent?alt=sse", headers, wrapped)
            }
            else -> return null
        }
        return request(endpoint, headers, body)
    }

    fun unwrap(connection: FriendModelConnection, document: JSONObject): JSONObject =
        if (connection.providerType == "gemini_pool") document.optJSONObject("response") ?: document else document

    private fun request(endpoint: String, headers: Map<String, String>, body: JSONObject): Request = Request.Builder()
        .url(endpoint).apply { providerSafeHeaders(headers).forEach { (key, value) -> header(key, value) } }
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
}
