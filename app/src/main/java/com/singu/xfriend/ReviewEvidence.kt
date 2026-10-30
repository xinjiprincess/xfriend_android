package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject

internal data class ReviewSource(val id: String, val text: String, val startSeconds: Double? = null,
    val asset: String = "", val region: String = "") {
    fun json() = JSONObject().put("id", id).put("text", text).put("startSeconds", startSeconds)
        .put("asset", asset).put("region", region)
    companion object {
        fun read(j: JSONObject) = ReviewSource(j.getString("id"), j.getString("text"),
            if (j.isNull("startSeconds")) null else j.getDouble("startSeconds"), j.optString("asset"), j.optString("region"))
    }
}

/** Only literal source quotes become evidence. Model-provided locations and IDs are never trusted. */
internal fun reviewEvidence(source: ReviewSource, quotes: JSONArray): List<JSONObject> = quotes.strings()
    .map(String::trim).filter { it.length >= 2 && source.text.contains(it) }.distinct().mapIndexed { index, quote ->
        JSONObject().put("id", "${source.id}-e${index + 1}").put("quote", quote)
            .put("startSeconds", source.startSeconds).apply {
                if (source.asset.isNotEmpty()) put("asset", source.asset)
                if (source.region.isNotEmpty()) put("region", source.region)
            }
    }

internal val reviewCitation = Regex("\\[(s[0-9]+(?:-[0-9]+)*-e[0-9]+)\\]")
internal fun validateReviewCitations(report: String, evidence: List<JSONObject>) {
    val ids = evidence.map { it.getString("id") }.toSet()
    val cited = reviewCitation.findAll(report).map { it.groupValues[1] }.toList()
    require(cited.all { it in ids } && (ids.isEmpty() || cited.isNotEmpty())) { "报告引用未通过原文核验，进度已保留，请重试" }
    // Also reject citations which look like evidence IDs but do not follow our generated ID format.
    val candidates = Regex("\\[([A-Za-z0-9_-]*[eE][0-9]+)\\]").findAll(report).map { it.groupValues[1] }
    require(candidates.all { it in ids }) { "报告包含不存在的证据引用" }
}

internal fun reviewJson(text: String): JSONObject = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

internal fun preservedReviewRaw(previous: String, current: String): String = when {
    previous.isBlank() -> current
    previous.contains(current) -> previous
    else -> previous + "\n\n[本次分析输入原文]\n" + current
}

internal fun reviewTextParts(text: String, limit: Int = 200000): List<String> {
    require(limit >= 2)
    val result = mutableListOf<String>(); var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + limit)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        result.add(text.substring(start, end)); start = end
    }
    return result
}

/** Suggestions are a separate artifact, never a Customer write. */
internal fun validatedReviewAnalysis(candidate: JSONObject, evidence: List<JSONObject>): JSONObject {
    val ids = evidence.map { it.getString("id") }.toSet()
    val profile = JSONObject()
    val input = candidate.optJSONObject("profile") ?: JSONObject()
    listOf("summary", "preferences", "needs", "budget", "timing", "decisionRole", "grade").forEach {
        profile.put(it, (input.opt(it) as? String)?.take(4000).orEmpty().ifBlank { "待确认" })
    }
    val actions = candidate.optJSONArray("actions")?.objects().orEmpty().mapNotNull { action ->
        val references = action.optJSONArray("evidenceIds").strings().distinct()
        val title = action.optString("title").trim()
        if (title.isBlank() || references.isEmpty() || references.any { it !in ids }) null else {
            val due = action.optString("dueDate").takeIf { runCatching { requireSalesDate(it) }.isSuccess }.orEmpty()
            JSONObject().put("title", title.take(200)).put("dueDate", due)
                .put("priority", action.optString("priority").takeIf { it in listOf("高", "普通", "低") } ?: "普通")
                .put("evidenceIds", JSONArray(references))
        }
    }
    val probability = JSONObject().put("reason", candidate.optJSONObject("probability")?.optString("reason").orEmpty().ifBlank { "证据不足，待确认" })
    candidate.optJSONObject("probability")?.let { value ->
        val low = value.optDouble("low", Double.NaN)
        val high = value.optDouble("high", Double.NaN)
        // Require an explicit rationale with real citations for a numerical estimate.
        val cited = reviewCitation.findAll(probability.getString("reason")).map { it.groupValues[1] }.toList()
        if (low.isFinite() && high.isFinite() && low >= 0 && high <= 100 && low <= high && cited.isNotEmpty() && cited.all { it in ids }) {
            probability.put("low", low).put("high", high)
        }
    }
    return JSONObject().put("profile", profile).put("actions", JSONArray(actions))
        .put("evidence", JSONArray(evidence)).put("probability", probability)
        .put("contactCandidate", validatedScreenshotContact(candidate.optJSONObject("contactCandidate"), evidence))
}

/** Exact suffix matching removes only substantial duplicate overlap. Raw text remains separate. */
internal fun joinReviewBoundary(left: String, right: String): String {
    val limit = minOf(600, left.length, right.length)
    val overlap = (limit downTo 8).firstOrNull { left.takeLast(it) == right.take(it) } ?: 0
    return left + (if (overlap == 0) "\n" else "") + right.drop(overlap)
}

internal class ReviewPartialWriter(private val intervalMs: Long = 1000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val write: (String) -> Unit) {
    private var last = Long.MIN_VALUE
    private var saved: String? = null
    fun accept(text: String, force: Boolean = false) {
        val now = clock()
        if (text != saved && (force || last == Long.MIN_VALUE || now - last >= intervalMs)) {
            write(text); saved = text; last = now
        }
    }
}

/** Only explicitly labelled, literally quoted contact fields may prefill the confirmation form. */
internal fun validatedScreenshotContact(candidate: JSONObject?, evidence: List<JSONObject>): JSONObject {
    val result = JSONObject()
    val labels = mapOf("name" to listOf("姓名", "联系人"), "company" to listOf("公司", "企业"), "phone" to listOf("电话", "手机", "联系电话"))
    labels.forEach { (field, allowedLabels) ->
        val entry = candidate?.optJSONObject(field) ?: return@forEach
        val value = (entry.opt("value") as? String)?.trim().orEmpty()
        val quote = (entry.opt("quote") as? String).orEmpty()
        val source = evidence.singleOrNull { it.optString("id") == entry.optString("evidenceId") } ?: return@forEach
        val labelled = allowedLabels.any { label -> Regex(Regex.escape(label) + "[：:]\\s*" + Regex.escape(value) + "(?=$|[\\s，,；;。])").containsMatchIn(quote) }
        if (entry.opt("explicit") == true && value.isNotBlank() && value.length <= (if (field == "company") 200 else if (field == "phone") 40 else 100) &&
            source.optString("asset").isNotBlank() && quote.isNotBlank() && source.optString("quote").contains(quote) && labelled) {
            result.put(field, entry)
        }
    }
    return result
}
