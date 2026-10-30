package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun FriendModelTransport.completeConnection(connection: FriendModelConnection, body: JSONObject,
    sessionId: String, onDelta: (String) -> Unit): JSONObject {
    val prepared = FriendProviderWire.prepare(connection, body, sessionId)
    return suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(prepared.request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(friendModelFailure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw friendHttpFailure(it.code)
                        val responseBody = it.body ?: throw FriendModelFailure("PROTOCOL", "模型响应为空")
                        val source = responseBody.source()
                        if (!friendBodyLooksLikeSse(source)) {
                            val buffer = okio.Buffer()
                            while (true) { val count = source.read(buffer, 8192); if (count < 0) break; require(buffer.size <= 2 * 1024 * 1024) }
                            providerResult(connection, JSONObject(buffer.readUtf8().removePrefix("\uFEFF"))).also { parsed ->
                                val text = parsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                                if (text.isNotEmpty() && continuation.isActive) onDelta(text)
                            }
                        } else {
                            val stream = FriendProviderStream(connection)
                            val data = StringBuilder(); var eventName = ""; var bytes = 0L
                            fun dispatch() {
                                if (data.isNotEmpty()) {
                                    stream.accept(eventName, data.toString().trimEnd('\n')) { delta -> if (continuation.isActive) onDelta(delta) }
                                    data.setLength(0)
                                }
                                eventName = ""
                            }
                            while (continuation.isActive && !stream.terminal && !source.exhausted()) {
                                val line = source.readUtf8LineStrict(262144).removePrefix("\uFEFF")
                                bytes += line.toByteArray(Charsets.UTF_8).size + 1; require(bytes <= 2 * 1024 * 1024)
                                when {
                                    line.isEmpty() -> dispatch()
                                    line.startsWith("event:") -> eventName = line.substring(6).trim()
                                    line.startsWith("data:") -> { data.append(line.substring(5).removePrefix(" ")).append('\n'); require(data.length <= 262144) }
                                }
                            }
                            if (data.isNotEmpty() && !stream.terminal) dispatch()
                            stream.result { delta -> if (continuation.isActive) onDelta(delta) }
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    call.cancel()
                    if (failure is CancellationException) continuation.cancel(failure)
                    else if (continuation.isActive) continuation.resumeWithException(friendModelFailure(failure))
                }
            }
        })
    }
}

internal suspend fun FriendModelTransport.testConnection(connection: FriendModelConnection, useTools: Boolean): String {
    val payload = JSONObject().put("model", connection.model).put("stream", true).put("max_tokens", 256)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK.")))
    if (useTools) payload.put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "connection_check").put("description", "Optional connection check")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)))))
        .put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "connection_check")))
    val response = completeConnection(connection, payload, UUID.randomUUID().toString()) {}
    val choice = response.getJSONArray("choices").getJSONObject(0)
    if (choice.optString("finish_reason") == "length") return "模型认证成功，但测试回复被截断；请调整输出预算"
    val calls = choice.getJSONObject("message").optJSONArray("tool_calls")?.objects().orEmpty()
    return if (useTools && calls.any { it.getJSONObject("function").optString("name") == "connection_check" }) "模型连接成功，已验证工具调用能力"
        else if (useTools) "模型连接成功，尚未验证工具调用能力"
        else "模型连接成功，纯聊天模式可用"
}
