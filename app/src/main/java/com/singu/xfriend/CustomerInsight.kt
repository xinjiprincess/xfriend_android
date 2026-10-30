package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject

internal data class CustomerInsight(
    val summary: String = "", val preferences: String = "", val needs: String = "",
    val budget: String = "", val timing: String = "", val decisionRole: String = "",
    val grade: String = "待判断",
) {
    fun json() = JSONObject().put("summary", summary).put("preferences", preferences)
        .put("needs", needs).put("budget", budget).put("timing", timing)
        .put("decisionRole", decisionRole).put("grade", grade)

    companion object {
        fun read(j: JSONObject) = CustomerInsight(j.optString("summary"), j.optString("preferences"),
            j.optString("needs"), j.optString("budget"), j.optString("timing"),
            j.optString("decisionRole"), j.optString("grade", "待判断"))
    }
}

/** Suggestions remain read-only until the user explicitly saves an editor. Percentages are 0..100. */
internal data class CustomerAnalysis(
    val profile: CustomerInsight, val actions: List<CustomerAction>, val evidence: List<String>,
    val low: String, val high: String, val reason: String,
) {
    val hasProbability: Boolean get() = validCustomerProbability(low, high) && low.isNotBlank()
    companion object {
        fun read(raw: String): CustomerAnalysis? = runCatching {
            val j = JSONObject(raw)
            val actions = j.optJSONArray("actions") ?: JSONArray()
            val evidence = j.optJSONArray("evidence") ?: JSONArray()
            val probability = j.optJSONObject("probability") ?: JSONObject()
            CustomerAnalysis(CustomerInsight.read(j.optJSONObject("profile") ?: JSONObject()),
                (0 until actions.length()).mapNotNull { index ->
                    when (val value = actions.opt(index)) {
                        is String -> value.takeIf { it.isNotBlank() }?.let { CustomerAction(it) }
                        is JSONObject -> value.optString("title").ifBlank { value.optString("action") }
                            .takeIf { it.isNotBlank() }?.let { CustomerAction(it, value.optString("dueDate"), value.optString("dueTime"), value.optString("priority", "普通")) }
                        else -> null
                    }
                }, (0 until evidence.length()).mapNotNull { index ->
                    when (val value = evidence.opt(index)) {
                        is String -> value.takeIf { it.isNotBlank() }
                        is JSONObject -> value.optString("quote").ifBlank { value.optString("text") }.takeIf { it.isNotBlank() }
                        else -> null
                    }
                }, probability.optString("low"), probability.optString("high"), probability.optString("reason"))
        }.getOrNull()
    }
}

internal data class CustomerAction(val title: String, val dueDate: String = "", val dueTime: String = "", val priority: String = "普通")

internal fun validCustomerProbability(low: String, high: String): Boolean {
    if (low.isEmpty() && high.isEmpty()) return true
    val l = low.toBigDecimalOrNull() ?: return false
    val h = high.toBigDecimalOrNull() ?: return false
    return l >= java.math.BigDecimal.ZERO && h <= java.math.BigDecimal(100) && l <= h
}

/** Fill only supplied candidate fields; missing AI fields must never erase a confirmed portrait. */
internal fun CustomerInsight.withCandidate(candidate: CustomerInsight) = CustomerInsight(
    candidate.summary.ifBlank { summary }, candidate.preferences.ifBlank { preferences },
    candidate.needs.ifBlank { needs }, candidate.budget.ifBlank { budget }, candidate.timing.ifBlank { timing },
    candidate.decisionRole.ifBlank { decisionRole }, candidate.grade.takeUnless { it.isBlank() || it == "待判断" } ?: grade,
)

internal fun SalesData.withCustomerInsight(customerId: String, value: CustomerInsight): SalesData {
    require(customers.any { it.id == customerId }) { "客户不存在" }
    require(listOf(value.summary, value.preferences, value.needs, value.budget, value.timing, value.decisionRole, value.grade).all { it.length <= 12000 }) { "客户画像内容过长" }
    return copy(customers = customers.map { if (it.id == customerId) it.copy(insight = value) else it })
}
