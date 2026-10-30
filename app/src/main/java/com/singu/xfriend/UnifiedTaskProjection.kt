package com.singu.xfriend

import org.json.JSONObject

/**
 * A read-only projection over two separate facts: FriendRun executions and PhoneRepository
 * items/reminders. It never copies or mutates either store; source + id route back to the owner.
 */
internal data class UnifiedTask(
    val source: String,
    val id: String,
    val title: String,
    val state: String,
    val active: Boolean,
    val created: Long,
    val updated: Long,
    val detail: String = "",
    val parentId: String? = null,
)

internal object UnifiedTaskProjection {
    const val SOURCE_RUN = "run"
    const val SOURCE_PHONE = "phone"

    fun fromRuns(runs: List<FriendRun>): List<UnifiedTask> = runs.map { run ->
        val state = when {
            run.state == "completed" -> "completed"
            run.state == "running" -> "running"
            run.state == "queued" -> "queued"
            run.state == "requires_review" -> "requires_review"
            run.state == "failed" -> "failed"
            run.state == "interrupted" -> "interrupted"
            run.state == "cancelled" -> "cancelled"
            else -> run.state
        }
        UnifiedTask(
            source = SOURCE_RUN,
            id = run.id,
            title = run.input.take(80).ifBlank { "星助理任务" },
            state = state,
            active = run.active,
            created = run.created,
            updated = run.updated,
            detail = run.errorMessage.take(200),
            parentId = run.agentParentId ?: run.parentId,
        )
    }

    fun fromPhone(items: List<JSONObject>): List<UnifiedTask> = items.map { item ->
        val raw = item.optString("status")
        val kind = item.optString("kind")
        val state = when (raw) {
            "saved" -> "completed"
            "scheduled", "open", "pending" -> "open"
            "delivered" -> "completed"
            "cancelled" -> "cancelled"
            "missed" -> "missed"
            "delivery_blocked", "needs_permission", "needs_time" -> "requires_review"
            else -> raw.ifBlank { "open" }
        }
        val due = item.optString("due_date")
        val title = item.optString("title").ifBlank { item.optString("name") }
        UnifiedTask(
            source = SOURCE_PHONE,
            id = item.optString("id"),
            title = title.take(80),
            state = state,
            active = state == "open",
            created = item.optLong("created_at"),
            updated = item.optLong("next_at", item.optLong("created_at")),
            detail = buildString {
                if (due.isNotBlank()) append("目标日期 ").append(due)
                if (item.has("next_at") && item.optLong("next_at") > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("提醒时间 ").append(item.optLong("next_at"))
                }
                if (isNotEmpty() && kind.isNotBlank()) append(" · ")
                if (kind.isNotBlank()) append(kind)
            },
        )
    }

    fun merge(runs: List<FriendRun>, phoneItems: List<JSONObject>): List<UnifiedTask> =
        (fromRuns(runs) + fromPhone(phoneItems)).sortedWith(compareByDescending<UnifiedTask> { it.active }.thenByDescending { it.updated })

    fun filter(tasks: List<UnifiedTask>, filter: String): List<UnifiedTask> = when (filter) {
        "进行中" -> tasks.filter { it.active }
        "待处理" -> tasks.filter { it.state in setOf("interrupted", "failed", "requires_review", "missed") }
        "已完成" -> tasks.filter { it.state == "completed" }
        "事项与提醒" -> tasks.filter { it.source == SOURCE_PHONE }
        else -> tasks
    }

    fun page(tasks: List<UnifiedTask>, page: Int, pageSize: Int = 10): List<UnifiedTask> =
        tasks.drop(page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).take(pageSize.coerceAtLeast(1))
}
