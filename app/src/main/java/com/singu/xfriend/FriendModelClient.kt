package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Contains only a stable category and a random support reference, never response/credential data. */
class FriendModelFailure(val code: String, val userMessage: String, val diagnosticId: String = UUID.randomUUID().toString()) :
    Exception(userMessage)

fun normalizeFriendModelBaseUrl(raw: String): String {
    val url = try { raw.trim().toHttpUrl() } catch (_: Exception) {
        throw FriendModelFailure("CONFIG_URL", "模型接口地址无效，请填写 HTTPS 地址")
    }
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
        throw FriendModelFailure("CONFIG_URL", "接口地址必须使用 HTTPS，且不能包含账号、查询参数或片段")
    val path = url.encodedPath.trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
    return url.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).build().toString().trimEnd('/')
}

fun friendModelFailure(error: Throwable): FriendModelFailure = when (error) {
    is CancellationException -> throw error
    is java.io.EOFException -> FriendModelFailure("PROTOCOL", "模型响应中断或格式不完整")
    is java.net.ProtocolException -> FriendModelFailure("PROTOCOL", "模型返回格式不兼容或响应不完整")
    is FriendModelFailure -> error
    is FriendProviderFailure -> FriendModelFailure(error.code, error.userMessage)
    is UnknownHostException -> FriendModelFailure("DNS", "无法解析模型服务器地址，请检查接口地址和网络")
    is SSLException -> FriendModelFailure("TLS", "模型服务器证书或安全连接验证失败")
    is SocketTimeoutException -> FriendModelFailure("TIMEOUT", "模型服务器响应超时，请稍后重试")
    is ConnectException -> FriendModelFailure("CONNECT", "无法连接模型服务器，请检查网络和接口地址")
    is IOException -> FriendModelFailure("CONNECT", "模型连接中断，请检查网络后重试")
    else -> FriendModelFailure("PROTOCOL", "模型返回格式不兼容或响应不完整")
}

internal fun friendHttpFailure(code: Int): FriendModelFailure = when (code) {
    400, 422 -> FriendModelFailure("HTTP_$code", "模型请求参数或协议不兼容，请核对模型名称和工具开关")
    402 -> FriendModelFailure("HTTP_402", "模型服务额度不足或需要付款，请检查账户余额和套餐")
    405 -> FriendModelFailure("HTTP_405", "模型接口路径或请求方法不兼容，请核对接口地址")
    413 -> FriendModelFailure("HTTP_413", "模型请求输入过大，请减少输入内容或附件后重新发送")
    401 -> FriendModelFailure("HTTP_401", "模型密钥无效或已过期，请检查模型配置")
    403 -> FriendModelFailure("HTTP_403", "没有访问该模型的权限，请检查服务授权")
    404 -> FriendModelFailure("HTTP_404", "模型或接口不存在，请检查模型名称和接口地址")
    429 -> FriendModelFailure("HTTP_429", "模型服务限流或额度不足，请稍后重试或检查额度")
    in 500..599 -> FriendModelFailure("HTTP_$code", "模型服务暂时故障，请稍后重试")
    else -> FriendModelFailure("HTTP_$code", "模型接口拒绝请求，请检查模型配置或联系服务提供商")
}

/**
 * Content-Type is advisory: several compatible gateways stream SSE as text/plain, or return a
 * complete JSON document with text/event-stream. Inspect the first non-empty line instead.
 */
internal fun friendBodyLooksLikeSse(source: okio.BufferedSource): Boolean {
    val peek = source.peek()
    return try {
        repeat(4) {
            val line = peek.readUtf8LineStrict(2 * 1024 * 1024).removePrefix("\uFEFF")
            if (line.isBlank()) return@repeat
            return line.startsWith("data:") || line.startsWith("event:") || line.startsWith("id:") ||
                line.startsWith("retry:") || line.startsWith(":")
        }
        false
    } catch (_: Throwable) { false }
}

internal class FriendModelTransport internal constructor(private val client: OkHttpClient) {
    internal val httpClient: OkHttpClient get() = client

    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS).build())

    suspend fun testConnection(baseUrl: String, model: String, key: String, useTools: Boolean): String {
        if (model.isBlank() || key.isBlank()) throw FriendModelFailure("CONFIG_MODEL", "请先配置 API 密钥和模型")
        val normalized = normalizeFriendModelBaseUrl(baseUrl)
        val payload = JSONObject().put("model", model).put("stream", true).put("max_tokens", 256)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK.")))
        if (normalized.toHttpUrl().host == "api.xiaomimimo.com") payload.put("thinking", JSONObject().put("type", "disabled"))
        if (useTools) {
            payload.put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "connection_check")))
            payload.put("tools", JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "connection_check").put("description", "Optional connection check")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)))))
        }
        val request = Request.Builder().url(normalized + "/chat/completions")
            .header("Authorization", "Bearer $key").header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val result = complete(request) {}
        val choice = result.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        if (choice.optString("finish_reason") == "length") return "模型认证连接成功，但测试响应被截断；请按模型需要调整输出预算"
        if (friendMessageText(message).isBlank() && message.optJSONArray("tool_calls").let { it == null || it.length() == 0 })
            throw FriendModelFailure("PROTOCOL", "连接已建立，但模型未返回完整有效的测试响应")
        val calls = message.optJSONArray("tool_calls")
        val toolsConfirmed = calls != null && (0 until calls.length()).any {
            calls.getJSONObject(it).getJSONObject("function").optString("name") == "connection_check"
        }
        return when {
            useTools && toolsConfirmed -> "模型连接成功，已验证工具调用能力"
            useTools -> "模型连接成功，已确认纯聊天响应；尚未验证工具调用能力"
            else -> "模型连接成功，纯聊天模式可用"
        }
    }

    internal suspend fun complete(request: Request, onDelta: (String) -> Unit): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(friendModelFailure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw friendHttpFailure(it.code)
                        val body = it.body ?: throw FriendModelFailure("PROTOCOL", "模型响应为空")
                        val source = body.source()
                        if (!friendBodyLooksLikeSse(source)) {
                            val buffer = okio.Buffer()
                            while (true) {
                                val n = source.read(buffer, 8192)
                                if (n < 0) break
                                require(buffer.size <= 262144)
                            }
                            val json = JSONObject(buffer.readUtf8().removePrefix("\uFEFF"))
                            normalizeFriendModelResponse(json)
                            validateFriendModelResponse(json)
                            val text = friendMessageText(json.getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
                            if (text.isNotEmpty() && continuation.isActive) onDelta(text)
                            json
                        } else {
                            val accumulator = FriendModelStream()
                            val data = StringBuilder()
                            var bytes = 0L
                            var done = false
                            fun dispatch() {
                                if (data.isEmpty()) return
                                val value = data.toString().trimEnd('\n'); data.setLength(0)
                                if (value == "[DONE]") { done = true; return }
                                accumulator.accept(JSONObject(value)) { text -> if (continuation.isActive) onDelta(text) }
                            }
                            while (!done && continuation.isActive && !source.exhausted()) {
                                val line = source.readUtf8LineStrict(262144).removePrefix("\uFEFF")
                                bytes += line.toByteArray(Charsets.UTF_8).size + 1
                                require(bytes <= 2 * 1024 * 1024)
                                when {
                                    line.isEmpty() -> dispatch()
                                    line.startsWith("data:") -> { data.append(line.substring(5).removePrefix(" ")).append('\n'); require(data.length <= 262144) }
                                }
                            }
                            if (!done && data.isNotEmpty()) dispatch()
                            accumulator.result(done).also { validateFriendModelResponse(it) }
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Throwable) {
                    // onDelta may reject revoked authorization before its owner cancels the job.
                    // Stop the socket and complete this suspension on the callback thread safely;
                    // never pass CancellationException through the rethrowing failure mapper.
                    call.cancel()
                    if (e is CancellationException) continuation.cancel(e)
                    else if (continuation.isActive) continuation.resumeWithException(friendModelFailure(e))
                }
            }
        })
    }
}

/** OpenAI-compatible delta assembly: interleaved calls are keyed by index, arguments remain raw until complete. */
internal class FriendModelStream {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var hasReasoning = false
    private val calls = sortedMapOf<Int, JSONObject>()
    private var finish: String? = null
    private fun normalizedReason(value: String?): String? = when (value) {
        null, "", "null" -> null
        "stop", "end_turn", "stop_sequence", "completed" -> "stop"
        "tool_calls", "tool_use", "function_call" -> "tool_calls"
        "length", "max_tokens", "max_output_tokens" -> "length"
        "content_filter" -> "content_filter"
        else -> null
    }
    fun accept(event: JSONObject, delta: (String) -> Unit) {
        if (event.has("error") && !event.isNull("error")) error("Provider error")
        // Usage-only chunks commonly have no choices. They are not protocol failures.
        val choices = event.optJSONArray("choices") ?: return
        for (i in 0 until choices.length()) {
            val choice = choices.getJSONObject(i)
            if (choice.optInt("index", 0) != 0) continue
            val part = choice.optJSONObject("delta") ?: JSONObject()
            if (!part.isNull("content")) {
                val content = when (val raw = part.opt("content")) {
                    is String -> raw
                    is JSONArray -> (0 until raw.length()).joinToString("") { index ->
                        val item = raw.optJSONObject(index) ?: return@joinToString ""
                        item.optString("text", item.optString("content", ""))
                    }
                    else -> ""
                }
                text.append(content); require(text.length <= 16000)
                if (content.isNotEmpty()) delta(content)
            }
            if (!part.isNull("reasoning_content")) {
                hasReasoning = true
                reasoning.append(part.getString("reasoning_content")); require(reasoning.length <= 262144)
            }
            val items = part.optJSONArray("tool_calls") ?: JSONArray()
            for (j in 0 until items.length()) {
                val item = items.getJSONObject(j)
                val index = item.optInt("index", j); require(index in 0..5)
                val call = calls.getOrPut(index) { JSONObject().put("id", "").put("type", "function")
                    .put("function", JSONObject().put("name", "").put("arguments", "")) }
                if (!item.isNull("id")) call.put("id", call.getString("id") + item.getString("id"))
                if (item.has("type")) require(item.getString("type") == "function")
                item.optJSONObject("function")?.let { function ->
                    for (key in listOf("name", "arguments")) if (!function.isNull(key)) {
                        val target = call.getJSONObject("function")
                        val value = target.getString(key) + function.getString(key)
                        require(value.length <= 65536); target.put(key, value)
                    }
                }
            }
            normalizedReason(if (choice.isNull("finish_reason")) null else choice.optString("finish_reason"))?.let { value ->
                // Some OpenAI-compatible proxies repeat the terminal reason with the last delta.
                if (finish == null) finish = value
            }
        }
    }
    fun result(done: Boolean): JSONObject {
        var terminal = finish
        if (terminal == null && done) terminal = if (calls.isNotEmpty()) "tool_calls" else if (text.isNotBlank()) "stop" else null
        require(terminal != null && terminal in setOf("stop", "tool_calls", "length", "content_filter")) { "Incomplete stream" }
        var reason = terminal
        require(reason != "content_filter")
        if (calls.isNotEmpty() && reason != "length") reason = "tool_calls"
        if (calls.isEmpty() && reason == "tool_calls") {
            require(text.isNotBlank()) { "Tool call stream has no calls" }
            reason = "stop"
        }
        finish = reason
        val message = JSONObject().put("role", "assistant").put("content", text.toString())
        if (hasReasoning) message.put("reasoning_content", reasoning.toString())
        if (calls.isNotEmpty() && reason != "length") {
            require(calls.keys.toList() == (0 until calls.size).toList())
            require(calls.values.all { it.getString("id").isNotBlank() && it.getJSONObject("function").getString("name").isNotBlank() })
            require(calls.values.map { it.getString("id") }.distinct().size == calls.size)
            calls.values.forEach { JSONObject(it.getJSONObject("function").getString("arguments")) }
            message.put("tool_calls", JSONArray(calls.values.toList()))
        } else require(reason != "tool_calls")
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", reason)))
    }
}

object FriendModelClient {
    private val transport = FriendModelTransport()
    suspend fun testConnection(baseUrl: String, model: String, key: String, useTools: Boolean): String =
        transport.testConnection(baseUrl, model, key, useTools)
}

internal fun friendMessageText(message: JSONObject): String {
    val raw = message.opt("content")
    if (raw is String) return raw
    if (raw is JSONArray) return (0 until raw.length()).joinToString("") { index ->
        val part = raw.optJSONObject(index) ?: return@joinToString ""
        part.optString("text", part.optString("content", ""))
    }
    return ""
}

/** Normalize common non-standard gateways before validation; never invent an answer or a tool. */
internal fun normalizeFriendModelResponse(json: JSONObject) {
    val choices = json.optJSONArray("choices") ?: return
    for (i in 0 until choices.length()) {
        val choice = choices.optJSONObject(i) ?: continue
        val message = choice.optJSONObject("message") ?: continue
        val text = friendMessageText(message)
        if (text.isNotEmpty() || message.has("content")) message.put("content", text)
        val calls = message.optJSONArray("tool_calls")
        val rawReason = choice.opt("finish_reason")
        val reason = when (rawReason) {
            is String -> when (rawReason) {
                "stop", "end_turn", "stop_sequence", "completed" -> "stop"
                "tool_calls", "tool_use", "function_call" -> "tool_calls"
                "length", "max_tokens", "max_output_tokens" -> "length"
                "content_filter" -> "content_filter"
                else -> null
            }
            else -> null
        } ?: when {
            calls != null && calls.length() > 0 -> "tool_calls"
            text.isNotBlank() -> "stop"
            else -> null
        }
        if (reason == "length" && calls != null) message.remove("tool_calls")
        if (reason != null && calls != null && calls.length() > 0 && reason != "length") choice.put("finish_reason", "tool_calls")
        else if (reason != null) choice.put("finish_reason", reason)
    }
}

internal fun validateFriendModelResponse(json: JSONObject) {
    val choices = json.getJSONArray("choices")
    require(choices.length() >= 1)
    val choice = choices.getJSONObject(0)
    require(choice.optString("finish_reason") in setOf("stop", "tool_calls", "length"))
    val message = choice.getJSONObject("message")
    val calls = message.optJSONArray("tool_calls")
    if (calls == null || calls.length() == 0) {
        val text = friendMessageText(message)
        require((text.isNotBlank() || choice.optString("finish_reason") == "length") && text.length <= 16000)
        require(choice.optString("finish_reason") != "tool_calls")
    } else {
        require(calls.length() <= 6 && choice.optString("finish_reason") == "tool_calls")
        val callIds = mutableSetOf<String>()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            require(call.getString("id").isNotBlank() && callIds.add(call.getString("id")))
            val function = call.getJSONObject("function")
            require(function.getString("name").isNotBlank())
            JSONObject(function.getString("arguments"))
        }
    }
}
