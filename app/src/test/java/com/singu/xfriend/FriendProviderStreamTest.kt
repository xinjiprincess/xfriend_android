package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendProviderStreamTest {
    private fun connection(format: String) = FriendModelConnection(id = "stream-connection", apiFormat = format,
        baseUrl = "https://provider.example.test/v1", model = "test-model", credentials = mapOf("api_key" to "test-api-key"))
    private fun canonical() = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "请求")))
        .put("max_tokens", 512)
    private fun choice(result: JSONObject) = result.getJSONArray("choices").getJSONObject(0)
    private fun message(result: JSONObject) = choice(result).getJSONObject("message")
    private fun feed(stream: FriendProviderStream, event: JSONObject, emitted: StringBuilder, eventName: String = "") {
        stream.accept(eventName, event.toString()) { emitted.append(it) }
    }
    private fun chatDelta(text: String? = null, finish: String? = null, calls: JSONArray? = null): JSONObject {
        val delta = JSONObject()
        if (text != null) delta.put("content", text)
        if (calls != null) delta.put("tool_calls", calls)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", finish ?: JSONObject.NULL)))
    }
    private fun outputText(text: String) = JSONObject().put("type", "message").put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private fun functionItem(id: String, name: String, arguments: String) = JSONObject().put("type", "function_call")
        .put("id", "item-$id").put("call_id", id).put("name", name).put("arguments", arguments)
    private fun response(text: String, status: String = "completed") = JSONObject().put("status", status).put("output", JSONArray().put(outputText(text)))
    private fun completedResponse(value: JSONObject) = JSONObject().put("type", "response.completed").put("response", value)
    private fun anthropic(type: String, index: Int? = null): JSONObject = JSONObject().put("type", type).apply { if (index != null) put("index", index) }
    private fun gemini(parts: JSONArray, finish: String? = null): JSONObject = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts)).apply { if (finish != null) put("finishReason", finish) }))

    @Test fun chatAcceptsFinishReasonWithoutTheOptionalDoneMarkerAndEmitsEachTextDeltaOnce() {
        val stream = FriendProviderStream(connection("openai_chat")); val emitted = StringBuilder()
        feed(stream, chatDelta("你"), emitted); feed(stream, chatDelta("好"), emitted)
        feed(stream, chatDelta(finish = "stop"), emitted)
        assertFalse(stream.terminal)
        val result = stream.result { emitted.append(it) }
        assertEquals("stop", choice(result).getString("finish_reason"))
        assertEquals("你好", message(result).getString("content")); assertEquals("你好", emitted.toString())
    }

    @Test fun interleavedChatCallsKeepTheirOwnArgumentFragmentsAndStableOrder() {
        val stream = FriendProviderStream(connection("openai_chat")); val emitted = StringBuilder()
        fun part(index: Int, id: String? = null, name: String? = null, args: String): JSONObject = JSONObject().put("index", index).apply {
            if (id != null) put("id", id)
            put("function", JSONObject().put("arguments", args).apply { if (name != null) put("name", name) })
        }
        feed(stream, chatDelta(calls = JSONArray().put(part(1, "call-b", "write_file", "{\"path\":\""))
            .put(part(0, "call-a", "read_file", "{\"path\":\"a"))), emitted)
        feed(stream, chatDelta(calls = JSONArray().put(part(0, args = ".txt\"}"))
            .put(part(1, args = "b.txt\",\"content\":\"正文\"}"))), emitted)
        feed(stream, chatDelta(finish = "tool_calls"), emitted); stream.accept("", "[DONE]") {}
        val result = stream.result {}; val calls = message(result).getJSONArray("tool_calls").objects()
        assertEquals(listOf("call-a", "call-b"), calls.map { it.getString("id") })
        assertEquals("a.txt", JSONObject(calls[0].getJSONObject("function").getString("arguments")).getString("path"))
        assertEquals("正文", JSONObject(calls[1].getJSONObject("function").getString("arguments")).getString("content"))
        assertEquals("tool_calls", choice(result).getString("finish_reason")); assertEquals("", emitted.toString())
    }

    @Test fun noProtocolTreatsEofOrPartialTextAsASuccessfulTerminalResponse() {
        for (format in friendProviderWireFormats) {
            val stream = FriendProviderStream(connection(format)); val emitted = StringBuilder()
            when (format) {
                "openai_chat" -> feed(stream, chatDelta("部分内容"), emitted)
                "openai_responses" -> feed(stream, JSONObject().put("type", "response.output_text.delta").put("delta", "部分内容"), emitted)
                "anthropic_messages" -> {
                    feed(stream, anthropic("content_block_start", 0).put("content_block", JSONObject().put("type", "text").put("text", "")), emitted)
                    feed(stream, anthropic("content_block_delta", 0).put("delta", JSONObject().put("type", "text_delta").put("text", "部分内容")), emitted)
                }
                "gemini_generate" -> feed(stream, gemini(JSONArray().put(JSONObject().put("text", "部分内容"))), emitted)
            }
            assertEquals("部分内容", emitted.toString()); assertFalse(stream.terminal)
            assertEquals("MODEL_STREAM_INCOMPLETE", assertThrows(FriendModelFailure::class.java) { stream.result {} }.code)
            assertEquals("部分内容", emitted.toString())
        }
    }

    @Test fun responsesFinalSnapshotMustMatchTheAlreadyDisplayedTextAndPreserveOpaqueReasoning() {
        val value = connection("openai_responses"); val stream = FriendProviderStream(value); val emitted = StringBuilder()
        val reasoning = JSONObject().put("type", "reasoning").put("id", "r1").put("encrypted_content", "opaque-evidence").put("summary", JSONArray())
        feed(stream, JSONObject().put("type", "response.output_item.added").put("output_index", 0).put("item", reasoning), emitted)
        feed(stream, JSONObject().put("type", "response.output_text.delta").put("delta", "你"), emitted)
        feed(stream, JSONObject().put("type", "response.output_text.delta").put("delta", "好"), emitted)
        val final = response("你好").put("output", JSONArray().put(reasoning).put(outputText("你好")))
        feed(stream, completedResponse(final), emitted)
        val result = stream.result { emitted.append(it) }
        assertEquals("你好", emitted.toString()); assertEquals("你好", message(result).getString("content"))
        assertEquals(value.id, message(result).getString("native_connection_id"))
        assertEquals("opaque-evidence", message(result).getJSONArray("native_response_items").getJSONObject(0).getString("encrypted_content"))
    }

    @Test fun responsesCanRecoverTheirFinalTextFromCompletedOutputItemsWithoutDuplicatingEmission() {
        val stream = FriendProviderStream(connection("openai_responses")); val emitted = StringBuilder()
        feed(stream, JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", outputText("只有最终快照")), emitted)
        feed(stream, completedResponse(JSONObject().put("status", "completed")), emitted)
        val result = stream.result { emitted.append(it) }
        stream.result { emitted.append(it) }
        assertEquals("只有最终快照", message(result).getString("content")); assertEquals("只有最终快照", emitted.toString())
    }

    @Test fun responsesInterleavedToolArgumentsNeverMixAcrossOutputIndexes() {
        val stream = FriendProviderStream(connection("openai_responses")); val emitted = StringBuilder()
        feed(stream, JSONObject().put("type", "response.output_item.added").put("output_index", 1).put("item", functionItem("a", "read_file", "")), emitted)
        feed(stream, JSONObject().put("type", "response.output_item.added").put("output_index", 2).put("item", functionItem("b", "write_file", "")), emitted)
        listOf(1 to "{\"path\":\"a", 2 to "{\"path\":\"b.txt\",", 1 to ".txt\"}", 2 to "\"content\":\"正文\"}").forEach { (index, delta) ->
            feed(stream, JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", index).put("delta", delta), emitted)
        }
        val a = functionItem("a", "read_file", "{\"path\":\"a.txt\"}")
        val b = functionItem("b", "write_file", "{\"path\":\"b.txt\",\"content\":\"正文\"}")
        feed(stream, JSONObject().put("type", "response.output_item.done").put("output_index", 1).put("item", a), emitted)
        feed(stream, JSONObject().put("type", "response.output_item.done").put("output_index", 2).put("item", b), emitted)
        feed(stream, completedResponse(JSONObject().put("status", "completed")), emitted)
        val calls = message(stream.result {}).getJSONArray("tool_calls").objects()
        assertEquals(listOf("a", "b"), calls.map { it.getString("id") })
        assertEquals("a.txt", JSONObject(calls[0].getJSONObject("function").getString("arguments")).getString("path"))
        assertEquals("正文", JSONObject(calls[1].getJSONObject("function").getString("arguments")).getString("content"))
        assertEquals("", emitted.toString())
    }

    @Test fun aResponsesTextMismatchNeverSilentlyReplacesTheVisiblePartialAnswer() {
        val stream = FriendProviderStream(connection("openai_responses")); val emitted = StringBuilder()
        feed(stream, JSONObject().put("type", "response.output_text.delta").put("delta", "已经显示的内容"), emitted)
        feed(stream, completedResponse(response("另一个最终内容")), emitted)
        assertEquals("MODEL_STREAM_INVALID", assertThrows(FriendModelFailure::class.java) { stream.result { emitted.append(it) } }.code)
        assertEquals("已经显示的内容", emitted.toString())
    }

    @Test fun responsesRefusalFailureAndCancellationNeverBecomeSuccessfulAnswers() {
        val refusal = FriendProviderStream(connection("openai_responses")); val emitted = StringBuilder()
        val output = JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "provider refusal"))))
        feed(refusal, completedResponse(JSONObject().put("status", "completed").put("output", output)), emitted)
        assertEquals("MODEL_REFUSED", assertThrows(FriendModelFailure::class.java) { refusal.result {} }.code)
        listOf("response.failed", "response.cancelled").forEach { type ->
            val stream = FriendProviderStream(connection("openai_responses"))
            assertThrows(FriendModelFailure::class.java) { feed(stream, JSONObject().put("type", type), emitted) }
            assertFalse(stream.terminal)
        }
        assertEquals("", emitted.toString())
    }

    @Test fun responsesLengthTerminationKeepsTextButNeverExposesTruncatedToolArgumentsAsExecutable() {
        val stream = FriendProviderStream(connection("openai_responses")); val emitted = StringBuilder()
        feed(stream, JSONObject().put("type", "response.output_text.delta").put("delta", "保留的半段"), emitted)
        val final = response("保留的半段", "incomplete").put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            .put("output", JSONArray().put(outputText("保留的半段")).put(functionItem("partial", "write_file", "{\"path\":")))
        feed(stream, JSONObject().put("type", "response.incomplete").put("response", final), emitted)
        val result = stream.result { emitted.append(it) }
        assertEquals("length", choice(result).getString("finish_reason")); assertFalse(message(result).has("tool_calls"))
        assertEquals("保留的半段", emitted.toString())
    }

    @Test fun anthropicThinkingSignatureAndInterleavedToolJsonStaySeparateFromDisplayedText() {
        val stream = FriendProviderStream(connection("anthropic_messages")); val emitted = StringBuilder()
        feed(stream, anthropic("content_block_start", 0).put("content_block", JSONObject().put("type", "thinking").put("thinking", "").put("signature", "")), emitted)
        feed(stream, anthropic("content_block_start", 1).put("content_block", JSONObject().put("type", "text").put("text", "")), emitted)
        feed(stream, anthropic("content_block_start", 2).put("content_block", JSONObject().put("type", "tool_use").put("id", "tool-1").put("name", "read_file").put("input", JSONObject())), emitted)
        feed(stream, anthropic("content_block_delta", 0).put("delta", JSONObject().put("type", "thinking_delta").put("thinking", "内部分析")), emitted)
        feed(stream, anthropic("content_block_delta", 1).put("delta", JSONObject().put("type", "text_delta").put("text", "可见")), emitted)
        feed(stream, anthropic("content_block_delta", 2).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"path\":\"a")), emitted)
        feed(stream, anthropic("content_block_delta", 0).put("delta", JSONObject().put("type", "signature_delta").put("signature", "opaque-signature")), emitted)
        feed(stream, anthropic("content_block_delta", 1).put("delta", JSONObject().put("type", "text_delta").put("text", "答复")), emitted)
        feed(stream, anthropic("content_block_delta", 2).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", ".txt\"}")), emitted)
        repeat(3) { feed(stream, anthropic("content_block_stop", it), emitted) }
        feed(stream, anthropic("message_delta").put("delta", JSONObject().put("stop_reason", "tool_use")), emitted)
        feed(stream, anthropic("message_stop"), emitted)
        val result = stream.result { emitted.append(it) }; val assistant = message(result)
        assertEquals("可见答复", emitted.toString()); assertEquals("可见答复", assistant.getString("content"))
        val thinking = assistant.getJSONArray("native_anthropic_content").getJSONObject(0)
        assertEquals("内部分析", thinking.getString("thinking")); assertEquals("opaque-signature", thinking.getString("signature"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("a.txt", JSONObject(call.getJSONObject("function").getString("arguments")).getString("path"))
    }

    @Test fun anthropicMessageDeltaWithoutMessageStopIsNotACompleteResponse() {
        val stream = FriendProviderStream(connection("anthropic_messages")); val emitted = StringBuilder()
        feed(stream, anthropic("content_block_start", 0).put("content_block", JSONObject().put("type", "text").put("text", "半段")), emitted)
        feed(stream, anthropic("content_block_stop", 0), emitted)
        feed(stream, anthropic("message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")), emitted)
        assertFalse(stream.terminal); assertThrows(FriendModelFailure::class.java) { stream.result {} }
        assertEquals("半段", emitted.toString())
    }

    @Test fun anthropicAndGeminiLengthStopsPreserveVisibleTextAndDisableToolExecution() {
        val anthropicValue = JSONObject().put("stop_reason", "max_tokens").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "半段"))
            .put(JSONObject().put("type", "tool_use").put("id", "tool").put("name", "write_file").put("input", JSONObject().put("path", "file"))))
        val geminiValue = gemini(JSONArray().put(JSONObject().put("text", "半段"))
            .put(JSONObject().put("functionCall", JSONObject().put("name", "write_file").put("args", JSONObject().put("path", "file")))), "MAX_TOKENS")
        listOf("anthropic_messages" to anthropicValue, "gemini_generate" to geminiValue).forEach { (format, raw) ->
            val result = providerResult(connection(format), raw)
            assertEquals("length", choice(result).getString("finish_reason")); assertEquals("半段", message(result).getString("content"))
            assertFalse(message(result).has("tool_calls"))
        }
    }

    @Test fun geminiAccumulatesVisibleTextAndRetainsNativeThoughtSignaturesAndToolIds() {
        val stream = FriendProviderStream(connection("gemini_generate")); val emitted = StringBuilder()
        feed(stream, gemini(JSONArray().put(JSONObject().put("text", "内部分析").put("thought", true).put("thoughtSignature", "thought-evidence"))
            .put(JSONObject().put("text", "前半"))), emitted)
        assertFalse(stream.terminal)
        feed(stream, gemini(JSONArray().put(JSONObject().put("text", "后半"))
            .put(JSONObject().put("functionCall", JSONObject().put("id", "native-call").put("name", "read_file").put("args", JSONObject().put("path", "a.txt")))
                .put("thoughtSignature", "tool-evidence")), "STOP"), emitted)
        val result = stream.result { emitted.append(it) }; val assistant = message(result)
        assertEquals("前半后半", emitted.toString()); assertEquals("前半后半", assistant.getString("content"))
        val parts = assistant.getJSONArray("native_gemini_parts")
        assertEquals("thought-evidence", parts.getJSONObject(0).getString("thoughtSignature"))
        assertEquals("tool-evidence", parts.getJSONObject(parts.length() - 1).getString("thoughtSignature"))
        assertEquals("native-call", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test fun geminiPoolSseUnwrapsTheAccountEnvelopeBeforeNormalizingTheAnswer() {
        val stream = FriendProviderStream(connection("gemini_generate").copy(providerType = "gemini_pool")); val emitted = StringBuilder()
        feed(stream, JSONObject().put("response", gemini(JSONArray().put(JSONObject().put("text", "账号池答复")), "STOP")), emitted)
        val result = stream.result { emitted.append(it) }
        assertEquals("账号池答复", message(result).getString("content")); assertEquals("账号池答复", emitted.toString())
    }

    @Test fun geminiBlockedOrMissingFinishReasonsNeverBecomeSuccessfulCompletions() {
        val blocked = FriendProviderStream(connection("gemini_generate")); val emitted = StringBuilder()
        assertThrows(FriendModelFailure::class.java) {
            feed(blocked, JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")), emitted)
        }
        val safety = FriendProviderStream(connection("gemini_generate"))
        feed(safety, gemini(JSONArray().put(JSONObject().put("text", "未确认内容")), "SAFETY"), emitted)
        assertThrows(FriendModelFailure::class.java) { safety.result {} }
    }

    @Test fun explicitStreamErrorsUseFixedMessagesAndDoNotEchoProviderSecrets() {
        for (format in friendProviderWireFormats) {
            val stream = FriendProviderStream(connection(format)); val emitted = StringBuilder()
            val failure = assertThrows(FriendModelFailure::class.java) {
                feed(stream, JSONObject().put("error", JSONObject().put("message", "provider-secret-should-not-be-echoed")), emitted)
            }
            assertEquals("MODEL_UPSTREAM_ERROR", failure.code)
            assertFalse(failure.message.orEmpty().contains("provider-secret-should-not-be-echoed"))
            assertEquals("", emitted.toString())
        }
    }

    @Test fun jsonNonStreamingResponsesNormalizeAllFourProtocolsWithoutInventingTerminalStatus() {
        val raw = mapOf(
            "openai_chat" to JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", "完整答复")))),
            "openai_responses" to response("完整答复"),
            "anthropic_messages" to JSONObject().put("stop_reason", "end_turn").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "完整答复"))),
            "gemini_generate" to gemini(JSONArray().put(JSONObject().put("text", "完整答复")), "STOP"),
        )
        raw.forEach { (format, document) ->
            val normalized = providerResult(connection(format), document)
            assertEquals("stop", choice(normalized).getString("finish_reason")); assertEquals("完整答复", message(normalized).getString("content"))
            val missing = JSONObject(document.toString())
            when (format) {
                "openai_chat" -> missing.getJSONArray("choices").getJSONObject(0).remove("finish_reason")
                "openai_responses" -> missing.remove("status")
                "anthropic_messages" -> missing.remove("stop_reason")
                "gemini_generate" -> missing.getJSONArray("candidates").getJSONObject(0).remove("finishReason")
            }
            if (format == "openai_chat") {
                // A complete text body without finish_reason is normalized to stop; no tool/answer is invented.
                assertEquals("stop", choice(providerResult(connection(format), missing)).getString("finish_reason"))
            } else assertThrows(Exception::class.java) { providerResult(connection(format), missing) }
        }
    }

    @Test fun explicitlyMalformedGeminiFunctionArgumentsCannotBecomeAnExecutableEmptyObject() {
        listOf<Any>("not-a-struct", JSONArray().put("not-a-struct")).forEach { malformed ->
            val raw = gemini(JSONArray().put(JSONObject().put("functionCall", JSONObject().put("id", "call")
                .put("name", "list_subagents").put("args", malformed))), "STOP")
            assertThrows(Exception::class.java) { providerResult(connection("gemini_generate"), raw) }
        }
    }

    @Test fun duplicateToolCallIdsAreRejectedAcrossEveryProtocolBeforeToolDispatch() {
        fun canonicalCall(name: String) = JSONObject().put("id", "duplicate").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", "{}"))
        val chat = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
            .put("message", JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", JSONArray().put(canonicalCall("list_subagents")).put(canonicalCall("list_workspace"))))))
        val responses = JSONObject().put("status", "completed").put("output", JSONArray()
            .put(functionItem("duplicate", "list_subagents", "{}").put("id", "unique-item-one"))
            .put(functionItem("duplicate", "list_workspace", "{}").put("id", "unique-item-two")))
        val anthropic = JSONObject().put("stop_reason", "tool_use").put("content", JSONArray()
            .put(JSONObject().put("type", "tool_use").put("id", "duplicate").put("name", "list_subagents").put("input", JSONObject()))
            .put(JSONObject().put("type", "tool_use").put("id", "duplicate").put("name", "list_workspace").put("input", JSONObject())))
        val gemini = gemini(JSONArray()
            .put(JSONObject().put("functionCall", JSONObject().put("id", "duplicate").put("name", "list_subagents").put("args", JSONObject())))
            .put(JSONObject().put("functionCall", JSONObject().put("id", "duplicate").put("name", "list_workspace").put("args", JSONObject()))), "STOP")
        listOf("openai_chat" to chat, "openai_responses" to responses,
            "anthropic_messages" to anthropic, "gemini_generate" to gemini).forEach { (format, raw) ->
            assertThrows(Exception::class.java) { providerResult(connection(format), raw) }
        }
    }

    @Test fun transportHandlesJsonAndSseFramingWithAnInMemoryInterceptorAndNoNetwork() = runBlocking {
        val fixtures = listOf(
            connection("anthropic_messages") to ("application/json" to JSONObject().put("stop_reason", "end_turn")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "JSON答复"))).toString()),
            connection("openai_responses") to ("text/event-stream" to (
                ": heartbeat\n\nevent: response.output_text.delta\ndata: " + JSONObject().put("type", "response.output_text.delta").put("delta", "SSE答复") + "\n\n" +
                    "event: response.completed\ndata: " + completedResponse(response("SSE答复")) + "\n\n")),
        )
        for ((value, fixture) in fixtures) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(fixture.second.toResponseBody(fixture.first.toMediaType())).build()
            }.build()
            try {
                val emitted = StringBuilder()
                val result = FriendModelTransport(client).completeConnection(value, canonical(), "session") { emitted.append(it) }
                assertEquals(1, requests)
                assertEquals(message(result).getString("content"), emitted.toString())
                assertEquals(if (fixture.first == "application/json") "JSON答复" else "SSE答复", emitted.toString())
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun textAndToolArgumentBoundsRejectOversizedStreamsBeforeExecution() {
        val stream = FriendProviderStream(connection("openai_responses")); val emitted = StringBuilder()
        assertThrows(IllegalArgumentException::class.java) {
            feed(stream, JSONObject().put("type", "response.output_text.delta").put("delta", "x".repeat(16001)), emitted)
        }
        assertEquals("", emitted.toString())
        val arguments = FriendProviderStream(connection("openai_responses"))
        assertThrows(IllegalArgumentException::class.java) {
            feed(arguments, JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", 0).put("delta", "x".repeat(65537)), emitted)
        }
    }
}
