package com.singu.xfriend

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal val friendProviderWireFormats = listOf("openai_chat", "openai_responses", "anthropic_messages", "gemini_generate")

fun normalizeFriendProviderBaseUrl(raw: String, apiFormat: String): String {
    if (apiFormat !in friendProviderWireFormats) throw FriendModelFailure("CONFIG_PROTOCOL", "不支持的模型协议")
    val url = try { raw.trim().toHttpUrl() } catch (_: Exception) { throw FriendModelFailure("CONFIG_URL", "请填写有效的 HTTPS 模型地址") }
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
        throw FriendModelFailure("CONFIG_URL", "模型地址必须使用 HTTPS，且不能包含账号、查询参数或片段")
    var path = url.encodedPath.trimEnd('/')
    path = when (apiFormat) {
        "openai_chat" -> path.removeSuffix("/chat/completions")
        "openai_responses" -> path.removeSuffix("/responses")
        "anthropic_messages" -> path.removeSuffix("/messages")
        else -> path
    }.trimEnd('/')
    return url.newBuilder().encodedPath(path.ifBlank { "/" }).build().toString().trimEnd('/')
}

internal data class ProviderWireRequest(val request: Request, val apiFormat: String)

internal object FriendProviderWire {
    fun prepare(connection: FriendModelConnection, canonicalBody: JSONObject, sessionId: String): ProviderWireRequest {
        if (!connection.enabled || connection.providerType in setOf("onnx", "claude_subscription", "gemini_subscription"))
            throw FriendModelFailure("MODEL_SUBSCRIPTION_QUOTA_ONLY", "此渠道不提供手机模型推理；请查看账号额度或选择 API 渠道")
        val base = normalizeFriendProviderBaseUrl(connection.baseUrl, connection.apiFormat)
        if (connection.model.isBlank() || connection.model.length > 256) throw FriendModelFailure("CONFIG_MODEL", "请先选择或填写模型")
        val key = connection.credentials["access_token"] ?: connection.credentials["api_key"].orEmpty()
        if ((key.isBlank() && connection.metadata["api_key_optional"] != "true" && connection.providerType != "gemini_pool") || key.any { it < ' ' || it == '\u007f' })
            throw FriendModelFailure("MODEL_AUTH_REQUIRED", "请先配置模型密钥或完成账号授权")
        if (connection.providerType == "volcengine_ark") validateArk(connection, base)
        if (connection.providerType == "codex" && (base != "https://chatgpt.com/backend-api/codex" || connection.apiFormat != "openai_responses"))
            throw FriendModelFailure("MODEL_AUTH_RESPONSE_INVALID", "Codex 订阅凭据只能用于其官方 Responses 渠道")
        if (connection.providerType == "xai_oauth" && (base != "https://api.x.ai/v1" || connection.apiFormat != "openai_responses"))
            throw FriendModelFailure("MODEL_AUTH_RESPONSE_INVALID", "xAI 订阅凭据只能用于其官方 Responses 渠道")
        val headers = linkedMapOf("Accept" to "text/event-stream")
        if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
        var body = JSONObject(canonicalBody.toString()).put("model", connection.model).put("stream", true)
        val endpoint: String
        when (connection.apiFormat) {
            "openai_chat" -> {
                endpoint = "$base/chat/completions"
                body.getJSONArray("messages").objects().forEach { message ->
                    message.keys().asSequence().filter { it.startsWith("native_") }.toList().forEach(message::remove)
                }
                // The official Chat API uses max_completion_tokens, including hidden reasoning tokens.
                if (base.toHttpUrl().host == "api.openai.com" && body.has("max_tokens"))
                    body.put("max_completion_tokens", body.remove("max_tokens"))
                if (base.toHttpUrl().host == "api.xiaomimimo.com") body.put("thinking", JSONObject().put("type", "disabled"))
            }
            "openai_responses" -> {
                endpoint = "$base/responses"
                body = responses(connection, body)
                if (connection.providerType == "codex") {
                    body.remove("temperature"); body.remove("top_p"); body.remove("max_output_tokens")
                    headers["chatgpt-account-id"] = connection.credentials["account_id"].orEmpty()
                    if (headers["chatgpt-account-id"].isNullOrBlank()) throw FriendModelFailure("MODEL_AUTH_REQUIRED", "Codex 授权缺少账号标识，请重新授权")
                    headers["OpenAI-Beta"] = "responses=experimental"
                    headers["originator"] = "xfriend"
                    headers["User-Agent"] = "xfriend-android/1"
                    headers["session-id"] = sessionId
                    body.put("prompt_cache_key", sessionId)
                }
            }
            "anthropic_messages" -> {
                endpoint = if (connection.providerType == "volcengine_ark") "$base/v1/messages" else "$base/messages"
                if (connection.providerType != "volcengine_ark") { headers.remove("Authorization"); headers["x-api-key"] = key }
                headers["anthropic-version"] = "2023-06-01"
                body = anthropic(connection, body)
            }
            "gemini_generate" -> {
                endpoint = base.toHttpUrl().newBuilder().addPathSegment("models")
                    .addPathSegment(connection.model.removePrefix("models/") + ":streamGenerateContent")
                    .addQueryParameter("alt", "sse").build().toString()
                headers.remove("Authorization"); headers["x-goog-api-key"] = key
                body = gemini(connection, body)
            }
            else -> throw FriendModelFailure("CONFIG_PROTOCOL", "不支持的模型协议")
        }
        val host = base.toHttpUrl().host
        if (connection.providerId.startsWith("opencode-") || host == "opencode.ai" || host.endsWith(".opencode.ai"))
            headers["x-opencode-session"] = sessionId.ifBlank { UUID.randomUUID().toString() }
        val custom = connection.credentials["headers"]?.let { runCatching { JSONObject(it) }.getOrNull() }
        custom?.keys()?.forEach { name ->
            if (name.lowercase() !in setOf("host", "content-length", "authorization", "x-api-key", "x-goog-api-key", "cookie", "connection", "x-refresh-token"))
                headers[name] = custom.getString(name)
        }
        val special = FriendProviderAccountTransport.prepare(connection, body, sessionId)
        if (special != null) return ProviderWireRequest(special, connection.apiFormat)
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return ProviderWireRequest(request, connection.apiFormat)
    }

    private fun validateArk(connection: FriendModelConnection, base: String) {
        val lane = when (connection.metadata["plan_type"]) { "agent" -> "plan"; "coding" -> "coding"; else -> throw FriendModelFailure("MODEL_ARK_ENDPOINT_MISMATCH", "请明确选择火山 Agent Plan 或 Coding Plan") }
        val suffix = if (connection.apiFormat == "anthropic_messages") "" else "/v3"
        if (connection.apiFormat !in listOf("openai_chat", "openai_responses", "anthropic_messages") || base != "https://ark.cn-beijing.volces.com/api/$lane$suffix")
            throw FriendModelFailure("MODEL_ARK_ENDPOINT_MISMATCH", "火山套餐渠道地址不匹配；不会改用按量计费接口")
    }

    private fun text(message: JSONObject): String = (message.opt("content") as? String).orEmpty()
    private fun tools(body: JSONObject) = body.optJSONArray("tools")?.objects().orEmpty()
    private fun messages(body: JSONObject) = body.getJSONArray("messages").objects()
    private fun native(message: JSONObject, connection: FriendModelConnection, key: String): JSONArray? =
        if (message.optString("native_connection_id") == connection.id) message.optJSONArray(key) else null

    internal fun responses(connection: FriendModelConnection, body: JSONObject): JSONObject {
        val out = JSONObject(body.toString()); out.remove("messages"); out.remove("stream_options")
        val input = JSONArray(); val instructions = mutableListOf<String>()
        for (message in messages(body)) {
            val role = message.getString("role")
            if (role == "system") { instructions += text(message); continue }
            if (role == "tool") { input.put(JSONObject().put("type", "function_call_output").put("call_id", message.getString("tool_call_id")).put("output", text(message))); continue }
            val replay = native(message, connection, "native_response_items")
            if (role == "assistant" && replay != null) { replay.objects().forEach(input::put); continue }
            val content = message.opt("content")
            if (content is String && content.isNotEmpty()) input.put(JSONObject().put("role", role).put("content", content))
            else if (content is JSONArray) input.put(JSONObject().put("role", role).put("content", content))
            message.optJSONArray("tool_calls")?.objects()?.forEach { call ->
                input.put(JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                    .put("name", call.getJSONObject("function").getString("name")).put("arguments", call.getJSONObject("function").getString("arguments")))
            }
        }
        out.put("input", input).put("instructions", instructions.joinToString("\n\n")).put("store", false)
        if (connection.providerType !in setOf("xai_oauth", "volcengine_ark")) out.put("include", JSONArray().put("reasoning.encrypted_content"))
        if (out.has("max_tokens")) out.put("max_output_tokens", out.remove("max_tokens"))
        out.optString("reasoning_effort").takeIf { it.isNotBlank() }?.let { out.put("reasoning", JSONObject().put("effort", it)) }; out.remove("reasoning_effort")
        if (tools(body).isNotEmpty()) out.put("tools", JSONArray(tools(body).map { tool ->
            val function = tool.getJSONObject("function")
            JSONObject().put("type", "function").put("name", function.getString("name"))
                .put("description", function.optString("description")).put("parameters", function.getJSONObject("parameters"))
        }))
        out.optJSONObject("tool_choice")?.optJSONObject("function")?.let { out.put("tool_choice", JSONObject().put("type", "function").put("name", it.getString("name"))) }
        return out
    }

    internal fun anthropic(connection: FriendModelConnection, body: JSONObject): JSONObject {
        val out = JSONObject(body.toString()); out.remove("stream_options"); out.remove("reasoning_effort")
        val turns = mutableListOf<JSONObject>(); val system = mutableListOf<String>()
        for (message in messages(body)) {
            val role = message.getString("role")
            if (role == "system") { system += text(message); continue }
            val blocks = JSONArray()
            if (role == "tool") blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", message.getString("tool_call_id")).put("content", text(message)))
            else {
                val replay = native(message, connection, "native_anthropic_content")
                if (replay != null) replay.objects().forEach(blocks::put)
                else {
                    text(message).takeIf { it.isNotEmpty() }?.let { blocks.put(JSONObject().put("type", "text").put("text", it)) }
                    message.optJSONArray("tool_calls")?.objects()?.forEach { call -> blocks.put(JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                        .put("name", call.getJSONObject("function").getString("name")).put("input", JSONObject(call.getJSONObject("function").getString("arguments")))) }
                }
            }
            val targetRole = if (role == "tool") "user" else role
            if (turns.lastOrNull()?.getString("role") == targetRole) blocks.objects().forEach { turns.last().getJSONArray("content").put(it) }
            else turns += JSONObject().put("role", targetRole).put("content", blocks)
        }
        out.put("system", system.joinToString("\n\n")).put("messages", JSONArray(turns))
        if (!out.has("max_tokens")) out.put("max_tokens", 2048)
        if (tools(body).isNotEmpty()) out.put("tools", JSONArray(tools(body).map { tool -> val function = tool.getJSONObject("function")
            JSONObject().put("name", function.getString("name")).put("description", function.optString("description")).put("input_schema", function.getJSONObject("parameters")) }))
        val choice = body.opt("tool_choice")
        if (choice is String) out.put("tool_choice", JSONObject().put("type", if (choice == "required") "any" else choice))
        else if (choice is JSONObject) choice.optJSONObject("function")?.let { out.put("tool_choice", JSONObject().put("type", "tool").put("name", it.getString("name"))) }
        return out
    }

    internal fun gemini(connection: FriendModelConnection, body: JSONObject): JSONObject {
        val turns = mutableListOf<JSONObject>(); val system = JSONArray(); val names = mutableMapOf<String, String>(); val nativeIds = mutableSetOf<String>()
        for (message in messages(body)) {
            message.optJSONArray("tool_calls")?.objects()?.forEach { names[it.getString("id")] = it.getJSONObject("function").getString("name") }
            native(message, connection, "native_gemini_parts")?.objects()?.forEach { it.optJSONObject("functionCall")?.optString("id")?.takeIf(String::isNotBlank)?.let(nativeIds::add) }
        }
        for (message in messages(body)) {
            val role = message.getString("role")
            if (role == "system") { system.put(JSONObject().put("text", text(message))); continue }
            val parts = JSONArray(); val replay = native(message, connection, "native_gemini_parts")
            if (role == "assistant" && replay != null) replay.objects().forEach(parts::put)
            else if (role == "tool") {
                val callId = message.getString("tool_call_id"); val name = names[callId] ?: throw FriendModelFailure("MODEL_TOOL_HISTORY_INVALID", "工具结果无法对应原调用，请新建任务")
                val response = JSONObject().put("name", name).put("response", JSONObject().put("result", text(message)))
                if (callId in nativeIds) response.put("id", callId)
                parts.put(JSONObject().put("functionResponse", response))
            } else {
                text(message).takeIf(String::isNotEmpty)?.let { parts.put(JSONObject().put("text", it)) }
                message.optJSONArray("tool_calls")?.objects()?.forEach { call -> parts.put(JSONObject().put("functionCall", JSONObject()
                    .put("name", call.getJSONObject("function").getString("name")).put("args", JSONObject(call.getJSONObject("function").getString("arguments"))))) }
            }
            val targetRole = if (role == "assistant") "model" else "user"
            if (turns.lastOrNull()?.getString("role") == targetRole) parts.objects().forEach { turns.last().getJSONArray("parts").put(it) }
            else turns += JSONObject().put("role", targetRole).put("parts", parts)
        }
        val config = JSONObject()
        for ((old, renamed) in listOf("max_tokens" to "maxOutputTokens", "temperature" to "temperature", "top_p" to "topP")) if (body.has(old)) config.put(renamed, body.get(old))
        val out = JSONObject().put("contents", JSONArray(turns)).put("generationConfig", config)
        if (system.length() > 0) out.put("systemInstruction", JSONObject().put("parts", system))
        if (tools(body).isNotEmpty()) out.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(tools(body).map { tool ->
            val function = tool.getJSONObject("function")
            JSONObject().put("name", function.getString("name")).put("description", function.optString("description")).put("parametersJsonSchema", function.getJSONObject("parameters"))
        }))))
        val choice = body.opt("tool_choice")
        val functionConfig = when {
            choice == "required" -> JSONObject().put("mode", "ANY")
            choice == "none" -> JSONObject().put("mode", "NONE")
            choice is JSONObject -> JSONObject().put("mode", "ANY").put("allowedFunctionNames", JSONArray().put(choice.getJSONObject("function").getString("name")))
            else -> JSONObject().put("mode", "AUTO")
        }
        if (tools(body).isNotEmpty()) out.put("toolConfig", JSONObject().put("functionCallingConfig", functionConfig))
        return out
    }
}
