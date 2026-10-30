package com.singu.xfriend

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of one voice turn: either editable text the user still has to send, or a classified failure. */
internal sealed interface StarTranscribe {
    data class Ok(val text: String) : StarTranscribe
    data class Failed(val code: String, val message: String) : StarTranscribe
}

/**
 * Records one short utterance, then hands the temporary file to an independent ASR endpoint.
 * The audio file lives in the app cache and is always deleted after transcription or cancellation;
 * it is never written to the conversation, memory, or logs.
 */
internal class StarVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var target: File? = null

    val recording: Boolean get() = recorder != null

    fun start(): Result<Unit> = runCatching {
        require(recorder == null) { "正在录音" }
        val file = File.createTempFile("star-voice-", ".m4a", context.cacheDir)
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(48_000)
            r.setMaxDuration(61_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (failure: Exception) {
            runCatching { r.release() }
            file.delete()
            throw failure
        }
        recorder = r; target = file
        Unit
    }

    /** Stops and returns the recording, or null when nothing usable was captured (for example, stopped too early). */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val file = target
        target = null
        val clean = try { r.stop(); true } catch (_: RuntimeException) { false } finally { runCatching { r.release() } }
        if (!clean) { file?.delete(); return null }
        return file?.takeIf { it.length() > 512 }
    }

    fun cancel() {
        val r = recorder
        recorder = null
        if (r != null) { runCatching { r.stop() }; runCatching { r.release() } }
        target?.delete()
        target = null
    }
}

/** OpenAI-compatible transcription endpoint. Any other ASR protocol needs its own adapter. */
internal suspend fun starTranscribe(context: Context, audio: File, store: Store): StarTranscribe = withContext(Dispatchers.IO) {
    try {
        val base = store.asrUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext StarTranscribe.Failed("asr_not_configured", "还没有配置语音识别服务，文字输入不受影响。")
        val model = store.asrModel.trim().ifBlank { "whisper-1" }
        val key = store.asrKey.trim().ifBlank { store.apiKey.trim() }
        val endpoint = if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "voice.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder().url(endpoint).post(body)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.build()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = when (response.code) {
                    401, 403 -> "auth_failed"
                    402 -> "quota_exceeded"
                    404 -> "model_not_found"
                    413 -> "audio_too_large"
                    429 -> "rate_limited"
                    in 500..599 -> "provider_error"
                    else -> "asr_error"
                }
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                return@use StarTranscribe.Failed(code, starAsrHint(code, response.code, detail))
            }
            val parsed = runCatching { JSONObject(text).optString("text") }.getOrNull().orEmpty().trim()
            if (parsed.isBlank()) StarTranscribe.Failed("no_speech", "没有识别到语音内容，请靠近麦克风再说一次。")
            else StarTranscribe.Ok(parsed)
        }
    } catch (offline: IOException) {
        StarTranscribe.Failed("network_unreachable", "连不上语音识别服务，请检查网络或地址。")
    } catch (failure: Exception) {
        StarTranscribe.Failed("asr_error", "语音识别失败：${failure.message ?: "未知错误"}")
    } finally {
        audio.delete()
    }
}

private fun starAsrHint(code: String, status: Int, detail: String): String = when (code) {
    "auth_failed" -> "语音识别鉴权失败（$status）。检查 ASR 凭据，文字输入仍可用。"
    "quota_exceeded" -> "语音识别额度不足（$status），本次未转写。"
    "model_not_found" -> "语音识别地址或模型不存在（$status）。确认地址以 /v1 结尾，模型名手填正确。"
    "audio_too_large" -> "录音过长，请分次录入。"
    "rate_limited" -> "语音识别被限流（$status），稍后再试。"
    "provider_error" -> "语音识别服务异常（$status），本次未转写。"
    else -> "语音识别失败（$status）${if (detail.isBlank()) "" else "：${detail.take(160)}"}"
}
