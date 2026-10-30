package com.singu.xfriend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal fun reviewPathComponent(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
internal fun reviewPath(id: String) = "/v2/reviews/" + reviewPathComponent(id)

/** A review has one stable server ID and one credit, regardless of media count or internal retries. */
internal class ReviewTrial(private val repo: SalesRepository, private val api: SalesApi,
    private val item: Communication, private val payload: JSONObject, private val progress: (Int) -> Unit,
    private val save: (ReviewArtifact) -> Unit) {
    private val url = payload.getString("trialUrl")
    private val lease = payload.getJSONObject("lease")
    private suspend fun call(id: String, operation: String): JSONObject = api.trial(url, lease, JSONObject(), reviewPath(id) + "/" + operation)
    suspend fun run(): ReviewArtifact {
        val textParts = reviewTextParts(item.text)
        val sources = when {
            item.text.isNotBlank() -> textParts.indices.map { JSONObject().put("source_id", "text-${it + 1}").put("kind", "text") }
            item.kind == "audio" -> {
                val info = wavInfo(repo.file(item.file))
                require(info.bytes.toLong() <= info.rate.toLong() * info.channels * 2 * 3600) { "试用复盘支持最长 60 分钟录音，请使用自有模型处理更长录音" }
                require(info.rate == 16000 && info.channels == 1) { "请重新导入录音，试用需要标准单声道音频" }
                listOf(JSONObject().put("source_id", "audio-1").put("kind", "audio").put("asset", item.file))
            }
            item.kind == "image" -> reviewImageAssets(item).map { asset ->
                JSONObject().put("source_id", asset).put("kind", "image").put("asset", asset).apply {
                    reviewAssetRegion(asset).takeIf(String::isNotBlank)?.let { put("region", JSONArray(it.split(',').map(String::toInt))) }
                }
            }
            else -> error("沟通内容为空")
        }
        require(sources.size in 1..100) { "图片内容超过试用容量，请使用自有模型" }
        var id = payload.optString("reviewId")
        var remote = if (id.isNotBlank()) api.trial(url, lease, null, reviewPath(id)) else {
            api.trial(url, lease, JSONObject().put("request_id", payload.getString("requestId"))
                .put("roles", payload.getJSONArray("roles")).put("prompt", payload.getString("prompt"))
                .put("sources", JSONArray(sources)), "/v2/reviews").also {
                id = it.getString("id"); payload.put("reviewId", id); progress(2)
            }
        }
        if (remote.getString("state") in listOf("failed", "cancelled")) remote = call(id, "resume")
        if (remote.getString("state") == "draft") {
            val uploaded = payload.optJSONArray("reviewUploaded") ?: JSONArray().also { payload.put("reviewUploaded", it) }
            for ((index, source) in sources.withIndex()) {
                currentCoroutineContext().ensureActive()
                val sourceId = source.getString("source_id")
                if (sourceId !in uploaded.strings()) {
                    when (source.getString("kind")) {
                        "text" -> api.reviewTextUpload(url, lease, id, sourceId, textParts[index])
                        "audio" -> api.reviewUpload(url, lease, id, sourceId, repo.file(item.file), "audio/wav")
                        else -> api.reviewUpload(url, lease, id, sourceId, repo.file(sourceId), "image/jpeg")
                    }
                    uploaded.put(sourceId); progress(2 + (index + 1) * 8 / sources.size)
                }
            }
            remote = call(id, "start")
        }
        val writer = ReviewPartialWriter(intervalMs = 1500) { partial ->
            payload.put("trialPartial", partial)
            save(ReviewArtifact(item.text, item.rawTranscript, partial, ""))
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            payload.put("reviewEventId", remote.optLong("last_event_id"))
            if (remote.has("partial_report")) writer.accept(remote.optString("partial_report"))
            progress((10 + remote.optInt("percent").coerceIn(0, 100) * 89 / 100).coerceAtMost(99))
            when (remote.getString("state")) {
                "succeeded" -> return trialReviewArtifact(remote.getJSONObject("result"), item)
                "failed" -> error("试用复盘暂未完成，进度已保留，请重试")
                "cancelled" -> error("试用复盘已暂停，可从任务中心继续")
            }
            delay(2000)
            remote = api.trial(url, lease, null, reviewPath(id))
        }
    }
}

internal fun trialReviewArtifact(result: JSONObject, item: Communication): ReviewArtifact {
    val text = result.optString("correctedText").ifBlank { result.optString("text", item.text) }
    val raw = result.optString("rawTranscript").ifBlank { item.rawTranscript.ifBlank { text } }
    val evidence = result.optJSONArray("evidence")?.objects().orEmpty()
    val analysis = when (val value = result.opt("analysisJson")) {
        is JSONObject -> JSONObject(value.toString())
        is String -> JSONObject(value)
        else -> JSONObject().put("profile", JSONObject()).put("actions", JSONArray()).put("evidence", JSONArray(evidence)).put("probability", JSONObject().put("reason", "待确认"))
    }
    // Convert wire values to the CRM's local display contract without changing the response.
    analysis.optJSONObject("profile")?.let { profile ->
        listOf("preferences", "needs").forEach { key ->
            (profile.opt(key) as? JSONArray)?.let { values -> profile.put(key, values.strings().joinToString("；")) }
        }
    }
    analysis.optJSONArray("actions")?.objects()?.forEach { action ->
        action.put("priority", when (action.optString("priority")) {
            "high", "高" -> "高"
            "low", "低" -> "低"
            else -> "普通"
        })
    }
    val citedEvidence = analysis.optJSONArray("evidence")?.objects().orEmpty().ifEmpty { evidence }
    // Server validates quotes against each raw source; client additionally checks retained raw transcript.
    require(citedEvidence.all { it.optString("quote").isNotBlank() && raw.contains(it.getString("quote")) }) { "试用报告证据与原始转写不符，已保留任务" }
    val ids = citedEvidence.map { it.getString("id") }.toSet()
    val structured = result.optJSONObject("report")
    structured?.optJSONArray("findings")?.objects()?.forEach { finding ->
        require(finding.optJSONArray("citations").strings().all { it in ids }) { "试用报告引用不存在" }
    }
    val report = result.optString("reportText").ifBlank {
        structured?.let { j -> j.optString("summary") + "\n\n" + j.optJSONArray("findings")?.objects().orEmpty().joinToString("\n\n") {
            it.optString("text") + " " + it.optJSONArray("citations").strings().joinToString(" ") { id -> "[$id]" }
        } }.orEmpty()
    }
    require(report.isNotBlank() && text.isNotBlank()) { "试用报告未完整返回" }
    val citations = Regex("\\[((?:[A-Za-z0-9_-]+)(?:,\\s*[A-Za-z0-9_-]+)*)\\]").findAll(report)
        .flatMap { it.groupValues[1].split(',').map(String::trim).asSequence() }.toList()
    require(citations.all { it in ids } && (ids.isEmpty() || citations.isNotEmpty())) { "试用报告缺少有效证据引用" }
    analysis.optJSONArray("actions")?.objects()?.forEach {
        val references = it.optJSONArray("evidenceIds").strings()
        require(references.isNotEmpty() && references.all { id -> id in ids }) { "试用建议证据引用不存在" }
    }
    return ReviewArtifact(text, preservedReviewRaw(item.rawTranscript, raw), report, analysis.toString())
}
