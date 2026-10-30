package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Internal task operations inherit the parent's account and mode; they never grant new authority. */
internal interface RuntimeSubagentAdapter {
    suspend fun beforeModelRequest(reservedTokens: Long)
    suspend fun spawn(expertId: String, requestId: String, arguments: JSONObject): JSONObject
    suspend fun result(taskId: String): JSONObject
    suspend fun list(): JSONObject
    suspend fun cancel(taskId: String): JSONObject
}

/** Only a child task's detached history is exposed to its runtime, never the main conversation. */
internal class IsolatedAgentState(
    base: RuntimeState,
    private val run: FriendRun,
    private val persist: (Map<String, List<Message>>, Set<String>) -> Unit,
) : RuntimeState by base {
    init { FriendRunIds.run(run.contextId); require(run.agentParentId != null) }
    override val workspaceRoot = File(base.workspaceRoot, ".agents/${run.contextId}")
    override val historyLock = Any()
    override val configurationLock = base.configurationLock
    override var messages: Map<String, List<Message>> = run.agentMessages.mapValues { it.value.toList() }
    override var runtimeHistorySteps: Set<String> = run.agentHistorySteps.toSet()
    override var localRooms: List<LocalRoom>
        get() = emptyList()
        set(value) { require(value.isEmpty()) { "子任务不能修改主群聊" } }
    override fun save() = persist(messages, runtimeHistorySteps)
}

/** A root request's frozen conversation snapshot. It has no agent parent and never touches the
 * subagent context contract; completed output is merged into the visible conversation by the
 * service after the run finishes. */
internal class IsolatedRootState(
    base: RuntimeState,
    private val run: FriendRun,
    private val persist: (Map<String, List<Message>>, Set<String>) -> Unit,
) : RuntimeState by base {
    init {
        FriendRunIds.run(run.rootContextId)
        require(run.agentParentId == null && run.contextId.isBlank()) { "根上下文不能伪装成子代理" }
    }
    override val historyLock = Any()
    override val configurationLock = base.configurationLock
    override var messages: Map<String, List<Message>> = run.agentMessages.mapValues { it.value.toList() }
    override var runtimeHistorySteps: Set<String> = run.agentHistorySteps.toSet()
    override var localRooms: List<LocalRoom>
        get() = emptyList()
        set(value) { require(value.isEmpty()) { "独立根任务不能修改主群聊" } }
    override fun save() = persist(messages, runtimeHistorySteps)
}

/** Queue injection keeps this authorization, routing and budget boundary testable without Android. */
internal class PersistentSubagentAdapter(
    private val queue: FriendRunQueue,
    private val parentRunId: String,
    private val authorized: () -> Boolean,
    private val onQueued: () -> Unit = {},
) : RuntimeSubagentAdapter {
    private fun guard(): FriendRun {
        if (!authorized()) throw CancellationException("authorization_ended")
        return queue.runs().firstOrNull { it.id == parentRunId && it.state == "running" && !it.agentCancelled }
            ?: throw CancellationException("cancelled")
    }

    override suspend fun beforeModelRequest(reservedTokens: Long) {
        guard()
        // A resumed HTTP generation is a new charge. A checkpointed complete response makes no call.
        queue.admitModelRequest(parentRunId, FriendRunIds.newId(), reservedTokens)
    }

    override suspend fun spawn(expertId: String, requestId: String, arguments: JSONObject): JSONObject {
        guard()
        val task = arguments.getString("task").trim()
        val run = queue.spawnChild(parentRunId, expertId, task,
            "$parentRunId:spawn:${runtimeHash(requestId)}",
            title = arguments.optString("title", "").take(80),
            maxModelCalls = arguments.optInt("max_model_calls", 8),
            tokenBudget = arguments.optLong("token_budget", 32000),
            timeoutMillis = arguments.optLong("timeout_seconds", 180) * 1000)
        onQueued()
        return snapshot(run)
    }

    private fun accessible(target: FriendRun, currentRun: FriendRun, runs: Map<String, FriendRun>): Boolean {
        if (target.contextId.isBlank()) return false
        var current: FriendRun? = target
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            if (current.agentParentId == parentRunId) return true
            val parent = current.agentParentId?.let(runs::get) ?: break
            if (currentRun.contextId.isNotBlank() && parent.contextId == currentRun.contextId) return true
            current = parent
        }
        // A new main-chat turn may report the earlier tasks in that same conversation.
        // An individual child never gains access to its siblings or their transcripts.
        return currentRun.contextId.isBlank() && current != null && current.contextId.isBlank() &&
            current.resourceId == currentRun.resourceId && current.team == currentRun.team &&
            current.localRoomTurn?.roomId == currentRun.localRoomTurn?.roomId && current.created <= currentRun.created
    }

    private fun descendant(taskId: String): FriendRun {
        val currentRun = guard(); FriendRunIds.run(taskId)
        val runs = queue.runs().associateBy { it.id }
        val target = runs[taskId] ?: throw IllegalArgumentException("找不到该子任务")
        require(accessible(target, currentRun, runs)) { "只能查看或停止当前会话中的授权子任务" }
        return target
    }

    override suspend fun list(): JSONObject {
        val current = guard()
        val runs = queue.runs().associateBy { it.id }
        val visible = runs.values.filter { accessible(it, current, runs) }
            .groupBy { it.contextId }.values.map { attempts -> attempts.maxBy { it.created } }
            .sortedWith(compareByDescending<FriendRun> { it.active }.thenByDescending { it.updated })
        return JSONObject().put("total", visible.size).put("tasks", JSONArray(visible.take(8).map {
            snapshot(it).put("result_summary", if (it.state == "completed") it.partial.take(160) else "")
        }))
    }

    override suspend fun result(taskId: String): JSONObject = snapshot(descendant(taskId))
    override suspend fun cancel(taskId: String): JSONObject {
        val run = descendant(taskId)
        queue.cancel(run.id); onQueued()
        return snapshot(queue.runs().first { it.id == run.id })
    }

    private fun snapshot(run: FriendRun): JSONObject = JSONObject()
        .put("task_id", run.id).put("context_id", run.contextId).put("title", run.agentTitle)
        .put("state", run.state).put("parent_task_id", run.agentParentId)
        .put("model_calls", run.modelCallsUsed).put("max_model_calls", run.maxModelCalls)
        .put("budget_reserved", run.tokensReserved).put("token_budget", run.tokenBudget)
        .put("result_summary", if (run.state == "completed") run.partial.take(1600) else "")
        .put("message", run.errorMessage)
}

internal fun subagentToolDefinitions(): List<JSONObject> {
    fun tool(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                .put("required", JSONArray(required)).put("additionalProperties", false)))
    fun text() = JSONObject().put("type", "string")
    fun number(min: Int, max: Int) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
    return listOf(
        tool("spawn_subagent", "Delegate an independent bounded task with its own context and checkpoint. Include only necessary task instructions and explicit reference material. It inherits this mode and permissions. Returns a task card immediately; do not claim it is finished. Depth limit 2, concurrency 3, tree limit 8 children. Results are inspected with get_subagent_result.",
            JSONObject().put("task", text()).put("title", text()).put("max_model_calls", number(1, 8))
                .put("token_budget", number(2048, 64000)).put("timeout_seconds", number(15, 180)), listOf("task")),
        tool("list_subagents", "List up to eight current task cards. Main-chat turns may see earlier subagent summaries in the same conversation; individual subagents see only their descendants. Never exposes internal transcripts.",
            JSONObject(), emptyList()),
        tool("get_subagent_result", "Inspect one authorized task's durable status and bounded final summary. Main-chat turns may inspect earlier subagent tasks in the same conversation. Individual agents cannot inspect sibling or unrelated contexts. A queued/running status is not completion; do not busy-poll.",
            JSONObject().put("task_id", text()), listOf("task_id")),
        tool("cancel_subagent", "Stop one descendant and its descendants. This never undoes completed file writes. Unknown tool outcomes require review; cancellation is permanent for that context.",
            JSONObject().put("task_id", text()), listOf("task_id")),
    )
}
