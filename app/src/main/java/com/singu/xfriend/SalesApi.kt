package com.singu.xfriend

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun mimoAsr(model: String, data: String) = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", data))))))
internal fun mimoSpeech(profile: ModelProfile, text: String, reference: String = ""): JSONObject {
    require(text.isNotBlank() && text.length <= 3000) { "播报文本需在 3000 字以内" }
    require(profile.tts in listOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone")) { "请选择 MiMo 语音模型" }
    require(profile.tts != "mimo-v2.5-tts-voiceclone" || reference.startsWith("data:audio/")) { "声音复刻需要用户授权的参考音频" }
    require(profile.tts != "mimo-v2.5-tts-voicedesign" || profile.style.isNotBlank()) { "请填写音色描述" }
    val audio = JSONObject().put("format", "wav")
    if (!profile.tts.endsWith("voicedesign")) audio.put("voice", if (profile.tts.endsWith("voiceclone")) reference else profile.voice)
    return JSONObject().put("model", profile.tts).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", profile.style)).put(JSONObject().put("role", "assistant").put("content", text))).put("audio", audio)
}
internal fun chatBody(model: String, prompt: String, content: Any) = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt)).put(JSONObject().put("role", "user").put("content", content))).put("max_tokens", 3000)
internal class SalesApi {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(30, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()
    suspend fun json(request: Request, max: Int = 32 * 1024 * 1024): JSONObject = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request); cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(IOException(salesNetworkError(e), e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { r ->
                    check(r.isSuccessful) { when (r.code) { 400 -> "模型请求格式或模型名称不匹配，请检查模型配置"; 404 -> "模型接口地址或模型不存在，请检查 HTTPS 地址及模型名称"; 413 -> "服务拒绝过大的音频或文本，请检查分段配置"; 408, 504 -> "服务处理超时，任务进度已保留，请到任务中心重试"; 500, 502, 503 -> "模型服务暂时不可用，请稍后在任务中心重试"; 401, 403 -> "凭据已失效或无权访问"; 402 -> "五次试用已用完，请配置自己的模型或查看订阅入口"; 409 -> "任务仍在处理中，请稍后重试"; 429 -> "请求过于频繁，请稍后再试"; else -> "服务请求失败（${r.code}）" } }
                    val bytes = r.body?.byteStream()?.use { it.bounded(max) } ?: error("响应为空")
                    JSONObject(String(bytes, Charsets.UTF_8))
                } }
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }
    suspend fun model(profile: ModelProfile, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        requireSafeApiUrl(profile.url); require(profile.key.isNotBlank()) { "请填写自己的 API Key 或切换到免密钥试用" }
        json(Request.Builder().url(profile.url.trimEnd('/') + "/chat/completions").header("Authorization", "Bearer ${profile.key}").post(body.toString().toRequestBody("application/json".toMediaType())).build())
    }
    suspend fun streamModel(profile: ModelProfile, body: JSONObject, partial: (String) -> Unit): String {
        requireSafeApiUrl(profile.url); require(profile.key.isNotBlank()) { "请填写自己的 API Key" }
        val request = Request.Builder().url(profile.url.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${profile.key}")
            .post(JSONObject(body.toString()).put("stream", true).toString().toRequestBody("application/json".toMediaType())).build()
        return stream(request, partial)
    }
    internal suspend fun stream(request: Request, partial: (String) -> Unit): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(IOException(salesNetworkError(e), e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { r ->
                    check(r.isSuccessful) { "模型流式请求失败（${r.code}），进度已保留" }
                    val source = r.body ?: error("响应为空")
                    if (!source.contentType().toString().contains("text/event-stream", true)) {
                        content(JSONObject(String(source.byteStream().use { it.bounded(4 * 1024 * 1024) }, Charsets.UTF_8))).also(partial)
                    } else {
                        val output = StringBuilder(); var finished = false; var terminal = false
                        source.source().use { input ->
                            while (!input.exhausted()) {
                                if (!cont.isActive) throw kotlinx.coroutines.CancellationException()
                                val line = input.readUtf8LineStrict(1024 * 1024L)
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data == "[DONE]") { terminal = true; break }
                                if (data.isBlank()) continue
                                val event = JSONObject(data)
                                if (event.has("error")) error("模型流式响应中断，进度已保留")
                                val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
                                val finish = choice.optString("finish_reason")
                                require(finish !in listOf("length", "max_tokens")) { "模型输出被截断，进度已保留，请重试" }
                                val delta = choice.optJSONObject("delta")?.opt("content") as? String ?: ""
                                output.append(delta); require(output.length <= 1_000_000) { "报告超过长度限制" }
                                if (delta.isNotEmpty()) partial(output.toString())
                                if (finish == "stop") finished = true
                            }
                        }
                        require(finished && terminal && output.isNotBlank()) { "报告流未完整结束，进度已保留，请重试" }
                        output.toString()
                    }
                } }
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }
    suspend fun reviewUpload(url: String, lease: JSONObject, id: String, sourceId: String, file: java.io.File, mime: String): JSONObject {
        requireSafeApiUrl(url)
        val path = reviewPath(id) + "/uploads/" + reviewPathComponent(sourceId)
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) { file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; sink.write(buffer, 0, count) }
            } }
        }
        return json(Request.Builder().url(url.trimEnd('/') + path).header("X-Xfriend-Lease",
            Base64.getUrlEncoder().withoutPadding().encodeToString(lease.toString().toByteArray())).put(body).build())
    }
    suspend fun reviewTextUpload(url: String, lease: JSONObject, id: String, sourceId: String, text: String): JSONObject {
        requireSafeApiUrl(url)
        return json(Request.Builder().url(url.trimEnd('/') + reviewPath(id) + "/uploads/" + reviewPathComponent(sourceId))
            .header("X-Xfriend-Lease", Base64.getUrlEncoder().withoutPadding().encodeToString(lease.toString().toByteArray()))
            .put(text.toRequestBody("text/plain; charset=utf-8".toMediaType())).build())
    }
    fun content(j: JSONObject): String {
        val choice = j.getJSONArray("choices").getJSONObject(0)
        require(choice.optString("finish_reason") == "stop") { "模型输出未完整结束，请重试" }
        val text = choice.getJSONObject("message").opt("content") as? String
        require(!text.isNullOrBlank()) { "模型未返回文本" }
        return text
    }
    suspend fun trial(url: String, lease: JSONObject, body: JSONObject?, path: String = "/v1/tasks"): JSONObject = withContext(Dispatchers.IO) {
        requireSafeApiUrl(url)
        val request = Request.Builder().url(url.trimEnd('/') + path).header("X-Xfriend-Lease", Base64.getUrlEncoder().withoutPadding().encodeToString(lease.toString().toByteArray()))
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        json(request.build())
    }
    suspend fun desktop(address: String, code: String, includeSecrets: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val origin = companionOrigin(address); require(code.isNotBlank()) { "请输入电脑显示的配对码" }
        val local = client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()
        val form = okhttp3.FormBody.Builder().add("code", code).build()
        val cookie = local.newCall(Request.Builder().url(origin + "/mobile/pair").header("Origin", origin).post(form).build()).execute().use { r ->
            require(r.code == 303) { "配对失败，请检查配对码及电脑版登录状态" }
            r.headers.values("Set-Cookie").firstOrNull { it.startsWith("xfriend_mobile=") }?.substringBefore(';') ?: error("连接服务未返回会话")
        }
        try {
            val j = json(Request.Builder().url(origin + "/mobile/export?include_secrets=" + if (includeSecrets) "1" else "0").header("Origin", origin).header("Cookie", cookie).build(), 4 * 1024 * 1024)
            j
        } finally {
            runCatching { local.newCall(Request.Builder().url(origin + "/mobile/logout").header("Origin", origin).header("Cookie", cookie).post(ByteArray(0).toRequestBody()).build()).execute().close() }
        }
    }
}
internal fun java.io.InputStream.bounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (out.size() <= limit) { val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
    require(out.size() <= limit) { "文件或响应过大" }; return out.toByteArray()
}
