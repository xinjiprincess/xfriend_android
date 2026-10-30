package com.singu.xfriend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal data class ReviewCompletion(val text: String, val finishReason: String) {
    val truncated get() = finishReason in listOf("length", "max_tokens")
    val complete get() = finishReason == "stop" && text.isNotBlank()
}

internal fun reviewCompletion(response: JSONObject): ReviewCompletion {
    val choice = response.getJSONArray("choices").getJSONObject(0)
    return ReviewCompletion(choice.getJSONObject("message").opt("content") as? String ?: "", choice.optString("finish_reason"))
}

/** Each successful leaf is durable. A truncated leaf is split, never discarded or retried as a whole file. */
internal class ReviewTranscriber(private val checkpoint: JSONObject, private val persist: () -> Unit,
    private val request: suspend (ReviewAudioRange, Int) -> ReviewCompletion) {
    private val leaves = checkpoint.optJSONObject("leaves") ?: JSONObject().also { checkpoint.put("leaves", it) }
    private val attempts = checkpoint.optJSONObject("attempts") ?: JSONObject().also { checkpoint.put("attempts", it) }
    private fun archiveIncomplete(range: ReviewAudioRange) {
        val expanded = attempts.optJSONObject(range.key + "-expanded")
        val latest = expanded ?: attempts.optJSONObject(range.key) ?: return
        val completion = ReviewCompletion(latest.getString("text"), latest.getString("finish"))
        if (completion.complete || (expanded == null && completion.truncated)) return
        val history = checkpoint.optJSONObject("incompleteAttempts") ?: JSONObject().also { checkpoint.put("incompleteAttempts", it) }
        val rows = history.optJSONArray(range.key) ?: JSONArray().also { history.put(range.key, it) }
        val attempt = JSONObject().put("initial", attempts.optJSONObject(range.key)).put("expanded", expanded)
        if (rows.objects().none { it.toString() == attempt.toString() }) rows.put(attempt)
        attempts.remove(range.key); attempts.remove(range.key + "-expanded")
        persist()
    }
    suspend fun transcribe(range: ReviewAudioRange): List<ReviewSource> {
        currentCoroutineContext().ensureActive()
        leaves.optJSONArray(range.key)?.let { return it.objects().map(ReviewSource::read) }
        // Covers both a normal retry and process death immediately after the expanded response was saved.
        archiveIncomplete(range)
        val cached = attempts.optJSONObject(range.key)
        val response = if (cached != null) ReviewCompletion(cached.getString("text"), cached.getString("finish")) else {
            request(range, 4096).also {
                attempts.put(range.key, JSONObject().put("text", it.text).put("finish", it.finishReason)); persist()
            }
        }
        val result = if (response.truncated && range.seconds > 5) {
            splitReviewAudio(range).flatMap { transcribe(it) }
        } else {
            val final = if (response.truncated) {
                val retry = attempts.optJSONObject(range.key + "-expanded")
                if (retry != null) ReviewCompletion(retry.getString("text"), retry.getString("finish")) else {
                    request(range, 16384).also {
                        attempts.put(range.key + "-expanded", JSONObject().put("text", it.text).put("finish", it.finishReason)); persist()
                    }
                }
            } else response
            if (!final.complete) {
                archiveIncomplete(range)
                error("音频转写仍不完整，原始片段和进度已保存；请更换 ASR 模型或重试")
            }
            val text = final.text
            listOf(ReviewSource("s${range.startFrame}-${range.endFrame}", text, range.startSeconds))
        }
        leaves.put(range.key, JSONArray(result.map { it.json() })); persist()
        return result
    }
}
