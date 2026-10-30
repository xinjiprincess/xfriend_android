package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Convert every channel into the existing runtime contract, retaining opaque tool replay evidence. */
internal fun providerResult(connection: FriendModelConnection, raw: JSONObject): JSONObject {
    val value = FriendProviderAccountTransport.unwrap(connection, raw)
    if (value.has("error")) throw FriendModelFailure("MODEL_UPSTREAM_ERROR", "模型服务返回错误，请检查账号授权和额度")
    if (connection.apiFormat == "openai_chat") { normalizeFriendModelResponse(value); validateFriendModelResponse(value); return value }
    val text = StringBuilder(); val calls = JSONArray(); val message = JSONObject().put("role", "assistant")
    var finish = "stop"
    fun call(id: String, name: String, arguments: String) {
        if (finish == "length") return
        require(id.isNotBlank() && name.isNotBlank() && arguments.length <= 65536)
        JSONObject(arguments)
        calls.put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments)))
        require(calls.length() <= 6)
    }
    when (connection.apiFormat) {
        "openai_responses" -> {
            when (value.optString("status")) {
                "completed" -> Unit
                "incomplete" -> if (value.optJSONObject("incomplete_details")?.optString("reason") == "max_output_tokens") finish = "length" else incomplete()
                else -> incomplete()
            }
            val items = value.getJSONArray("output")
            items.objects().forEach { item -> when (item.optString("type")) {
                "message" -> item.optJSONArray("content")?.objects()?.forEach { part ->
                    if (part.optString("type") == "output_text") text.append(part.getString("text"))
                    else if (part.optString("type") == "refusal") throw FriendModelFailure("MODEL_REFUSED", "模型服务拒绝了本次请求")
                }
                "function_call" -> call(item.getString("call_id"), item.getString("name"), item.getString("arguments"))
                "reasoning" -> Unit
                else -> throw FriendModelFailure("MODEL_CONTENT_UNSUPPORTED", "模型返回了当前手机端未启用的工具或内容")
            } }
            message.put("native_response_items", items)
        }
        "anthropic_messages" -> {
            finish = when (value.optString("stop_reason")) {
                "end_turn", "stop_sequence" -> "stop"
                "tool_use" -> "tool_calls"
                "max_tokens" -> "length"
                else -> incomplete()
            }
            val blocks = value.getJSONArray("content")
            blocks.objects().forEach { block -> when (block.optString("type")) {
                "text" -> text.append(block.getString("text"))
                "tool_use" -> call(block.getString("id"), block.getString("name"), block.getJSONObject("input").toString())
                "thinking", "redacted_thinking" -> Unit
                else -> throw FriendModelFailure("MODEL_CONTENT_UNSUPPORTED", "模型返回了当前手机端未启用的内容")
            } }
            message.put("native_anthropic_content", blocks)
        }
        "gemini_generate" -> {
            val candidate = value.optJSONArray("candidates")?.optJSONObject(0) ?: incomplete()
            finish = when (candidate.optString("finishReason")) { "STOP" -> "stop"; "MAX_TOKENS" -> "length"; else -> incomplete() }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            parts.objects().forEachIndexed { index, part ->
                if (part.has("text") && !part.optBoolean("thought")) text.append(part.getString("text"))
                if (part.has("functionCall")) part.getJSONObject("functionCall").let { function ->
                    val args = if (function.has("args")) function.getJSONObject("args") else JSONObject()
                    call(function.optString("id").ifBlank { "gemini-$index-${UUID.randomUUID()}" }, function.getString("name"), args.toString())
                }
            }
            message.put("native_gemini_parts", parts)
        }
        else -> throw FriendModelFailure("CONFIG_PROTOCOL", "不支持的模型协议")
    }
    require(text.length <= 16000)
    if (calls.length() > 0 && finish != "length") finish = "tool_calls"
    message.put("content", text.toString()).put("native_connection_id", connection.id)
    if (calls.length() > 0) message.put("tool_calls", calls)
    val result = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish)))
    // A truncated tool argument is never executable. Preserve visible text, then runtime reports length.
    if (finish == "length" && calls.length() > 0) message.remove("tool_calls")
    validateFriendModelResponse(result)
    return result
}

private fun incomplete(): Nothing = throw FriendModelFailure("MODEL_STREAM_INCOMPLETE", "模型响应未完整结束，已保留收到的内容")

/** Vendor terminal markers are mandatory. Stream EOF alone is never success. */
internal class FriendProviderStream(private val connection: FriendModelConnection) {
    private val chat = FriendModelStream()
    private val text = StringBuilder()
    private val responseItems = sortedMapOf<Int, JSONObject>()
    private val anthropicBlocks = sortedMapOf<Int, JSONObject>()
    private val arguments = mutableMapOf<Int, StringBuilder>()
    private val geminiParts = mutableListOf<JSONObject>()
    private var anthropicReason = ""
    private var geminiReason = ""
    private var finalResponse: JSONObject? = null
    var terminal: Boolean = false
        private set

    fun accept(eventName: String, data: String, emit: (String) -> Unit) {
        if (data == "[DONE]") { require(connection.apiFormat == "openai_chat"); terminal = true; return }
        val event = FriendProviderAccountTransport.unwrap(connection, JSONObject(data))
        if (event.has("error") || eventName == "error" || event.optString("type") == "error")
            throw FriendModelFailure("MODEL_UPSTREAM_ERROR", "模型服务中断了请求，请检查授权和额度")
        when (connection.apiFormat) {
            "openai_chat" -> chat.accept(event) { appendText(it, emit) }
            "openai_responses" -> when (event.optString("type", eventName)) {
                "response.output_text.delta" -> appendText(event.getString("delta"), emit)
                "response.output_item.added" -> event.optJSONObject("item")?.let { responseItems[event.getInt("output_index")] = JSONObject(it.toString()) }
                "response.function_call_arguments.delta" -> arguments.getOrPut(event.getInt("output_index")) { StringBuilder() }.append(event.getString("delta")).also { require(it.length <= 65536) }
                "response.output_item.done" -> responseItems[event.getInt("output_index")] = event.getJSONObject("item")
                "response.completed", "response.done", "response.incomplete" -> {
                    val final = event.optJSONObject("response") ?: JSONObject()
                    if (!final.has("output") || final.optJSONArray("output")?.length() == 0) final.put("output", JSONArray(responseItems.values.toList()))
                    if (!final.has("status")) final.put("status", if (event.optString("type", eventName) == "response.incomplete") "incomplete" else "completed")
                    finalResponse = final; terminal = true
                }
                "response.failed", "response.cancelled" -> incomplete()
            }
            "anthropic_messages" -> when (event.optString("type", eventName)) {
                "content_block_start" -> {
                    val block = JSONObject(event.getJSONObject("content_block").toString())
                    anthropicBlocks[event.getInt("index")] = block
                    if (block.optString("type") == "text" && block.optString("text").isNotEmpty()) appendText(block.getString("text"), emit)
                }
                "content_block_delta" -> {
                    val index = event.getInt("index"); val block = anthropicBlocks[index] ?: incomplete(); val delta = event.getJSONObject("delta")
                    when (delta.optString("type")) {
                        "text_delta" -> { val value = delta.getString("text"); block.put("text", block.optString("text") + value); appendText(value, emit) }
                        "input_json_delta" -> arguments.getOrPut(index) { StringBuilder() }.append(delta.getString("partial_json")).also { require(it.length <= 65536) }
                        "thinking_delta" -> block.put("thinking", block.optString("thinking") + delta.getString("thinking"))
                        "signature_delta" -> block.put("signature", block.optString("signature") + delta.getString("signature"))
                    }
                    require(block.toString().length <= 262144)
                }
                "content_block_stop" -> arguments[event.getInt("index")]?.let { anthropicBlocks.getValue(event.getInt("index")).put("input", JSONObject(it.toString())) }
                "message_delta" -> anthropicReason = event.getJSONObject("delta").optString("stop_reason", anthropicReason)
                "message_stop" -> { terminal = true; finalResponse = JSONObject().put("stop_reason", anthropicReason).put("content", JSONArray(anthropicBlocks.values.toList())) }
            }
            "gemini_generate" -> {
                if (event.has("promptFeedback") && event.optJSONArray("candidates")?.length() != 1) incomplete()
                event.optJSONArray("candidates")?.optJSONObject(0)?.let { candidate ->
                    candidate.optJSONObject("content")?.optJSONArray("parts")?.objects()?.forEach { part ->
                        val previous = geminiParts.lastOrNull()
                        if (part.has("text") && !part.has("functionCall") && previous?.has("text") == true && previous.optBoolean("thought") == part.optBoolean("thought") && !part.has("thoughtSignature") && !previous.has("thoughtSignature"))
                            previous.put("text", previous.getString("text") + part.getString("text"))
                        else geminiParts += JSONObject(part.toString())
                        if (part.has("text") && !part.optBoolean("thought")) appendText(part.getString("text"), emit)
                    }
                    if (candidate.optString("finishReason").isNotBlank()) {
                        geminiReason = candidate.getString("finishReason"); terminal = true
                        finalResponse = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", geminiReason)
                            .put("content", JSONObject().put("parts", JSONArray(geminiParts)))))
                    }
                }
            }
        }
    }

    private fun appendText(value: String, emit: (String) -> Unit) { text.append(value); require(text.length <= 16000); if (value.isNotEmpty()) emit(value) }

    fun result(emit: (String) -> Unit): JSONObject {
        // The finish_reason is the OpenAI-compatible terminal signal. Some gateways omit the
        // optional [DONE] sentinel, so EOF with a valid finish must not become a protocol error.
        if (connection.apiFormat == "openai_chat") {
            val result = try { chat.result(terminal) } catch (_: IllegalArgumentException) { incomplete() }
            validateFriendModelResponse(result)
            return result
        }
        if (!terminal) incomplete()
        val result = providerResult(connection, finalResponse ?: incomplete())
        val completeText = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        if (text.isEmpty() && completeText.isNotEmpty()) appendText(completeText, emit)
        else if (text.toString() != completeText) throw FriendModelFailure("MODEL_STREAM_INVALID", "模型流式内容与最终结果不一致，已保留收到的内容")
        return result
    }
}
