package com.singu.xfriend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Resume after the last durable tool response, rather than replaying completed proposal calls. */
internal suspend fun reviewToolLoop(state: JSONObject, key: String, model: String, system: String, input: String,
    definitions: JSONArray, persist: () -> Unit, request: suspend (JSONObject) -> JSONObject,
    execute: suspend (String, JSONObject) -> String): String {
    val loops = state.optJSONObject("toolLoops") ?: JSONObject().also { state.put("toolLoops", it) }
    val checkpoint = loops.optJSONObject(key) ?: JSONObject().put("messages", JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", input))).also { loops.put(key, it) }
    if (checkpoint.has("answer")) return checkpoint.getString("answer")
    val messages = checkpoint.getJSONArray("messages")
    repeat(12) {
        currentCoroutineContext().ensureActive()
        val pending = checkpoint.optJSONArray("pending")
        if (pending != null) {
            val completed = checkpoint.optInt("completedTools")
            for (i in completed until pending.length()) {
                val call = pending.getJSONObject(i)
                val function = call.getJSONObject("function")
                val result = try { execute(function.getString("name"), JSONObject(function.getString("arguments"))) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: IllegalArgumentException) { JSONObject().put("error", "工具参数无效或数据不在本次授权范围；请修正查询，不得猜测结果").toString() }
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result))
                checkpoint.put("completedTools", i + 1); persist()
            }
            checkpoint.remove("pending"); checkpoint.remove("completedTools"); persist()
        }
        require(messages.toString().length <= 100000) { "专家检索上下文过长，请缩小检索范围后重试" }
        val response = request(JSONObject().put("model", model).put("messages", messages).put("tools", definitions)
            .put("tool_choice", "auto").put("max_tokens", 3000))
        val choice = response.getJSONArray("choices").getJSONObject(0)
        require(choice.optString("finish_reason") !in listOf("length", "max_tokens")) { "专家输出被截断，进度已保存" }
        val message = choice.getJSONObject("message")
        val calls = message.optJSONArray("tool_calls")
        if (calls == null || calls.length() == 0) {
            val answer = message.opt("content") as? String ?: error("专家未返回文本")
            require(answer.isNotBlank())
            checkpoint.put("answer", answer); persist(); return answer
        }
        require(calls.length() <= 6) { "专家一次请求的工具过多" }
        val names = definitions.objects().map { it.getJSONObject("function").getString("name") }.toSet()
        require(calls.objects().all { it.getJSONObject("function").getString("name") in names }) { "专家请求了未授权工具" }
        messages.put(message); checkpoint.put("pending", calls).put("completedTools", 0); persist()
    }
    error("专家检索尚未完成，进度已保存，请继续任务")
}
