package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Contains user content, never model configuration, API keys or authorization tokens. */
data class FriendRun(
    val id: String,
    val resourceId: String,
    val input: String,
    val team: Boolean,
    val clientRequestId: String,
    val state: String = "queued",
    val partial: String = "",
    val errorCode: String = "",
    val diagnosticId: String = "",
    val errorMessage: String = "等待运行",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
    val parentId: String? = null,
    val checkpoint: String? = null,
    val verifyRequested: Boolean = false,
    val conversationMode: String = ConversationMode.CRAFT,
    val localRoomTurn: LocalRoomTurn? = null,
    /** Message-level reply snapshot: the run carries the quoted target into the worker. */
    val replyToId: String = "",
    val replyToRole: String = "",
    val replyPreview: String = "",
    /** Stable id of the user message that created this task, used to bind status and the answer. */
    val requestMessageId: String = "",
    /**
     * Independent execution context for an ordinary root request. It is deliberately separate
     * from [contextId]/[agentParentId], which remain the subagent contract.
     */
    val rootContextId: String = "",
    /** Independent agent graph, separate from parentId (the retry/follow-up attempt chain). */
    val contextId: String = "",
    val agentParentId: String? = null,
    val agentDepth: Int = 0,
    val agentTitle: String = "",
    val maxModelCalls: Int = 8,
    val modelCallsUsed: Int = 0,
    val tokenBudget: Long = 64_000,
    val tokensReserved: Long = 0,
    val deadlineAt: Long = 0,
    val agentMessages: Map<String, List<Message>> = emptyMap(),
    val agentHistorySteps: Set<String> = emptySet(),
    val modelReservations: Map<String, Long> = emptyMap(),
    val agentCancelled: Boolean = false,
    val agentTimeoutMillis: Long = 0,
) {
    val active: Boolean get() = state == "queued" || state == "running"
    val canResume: Boolean get() = !agentCancelled && state in setOf("interrupted", "failed") && checkpoint != null &&
        errorCode !in setOf("CHECKPOINT_MODE_CHANGED", "CHECKPOINT_CONFIG_CHANGED", "CHECKPOINT_INPUT_CHANGED", "CHECKPOINT_REVIEW") && !friendCheckpointNeedsReview(checkpoint)
    val canVerifyCheckpoint: Boolean get() = !agentCancelled && state in setOf("interrupted", "failed", "requires_review") && friendCheckpointCanVerify(checkpoint)
    // Avoid accidentally printing private prompts and model output to logs.
    override fun toString(): String = "FriendRun(id=$id,state=$state,content=redacted)"
}

internal object FriendRunIds {
    fun scope(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) { "无效账号空间" } }
    fun run(value: String) { require(value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "无效任务编号" } }
    fun resource(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "无效专家或团队编号" } }
    fun request(value: String) { require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "无效请求编号" } }
    fun newId(): String = UUID.randomUUID().toString()
}

internal object FriendRunCodec {
    fun encode(runs: List<FriendRun>): String = JSONObject().put("version", 1).put("runs", JSONArray(runs.map { r ->
        JSONObject().put("id", r.id).put("resourceId", r.resourceId).put("input", r.input)
            .put("team", r.team).put("clientRequestId", r.clientRequestId).put("state", r.state)
            .put("diagnosticId", r.diagnosticId).put("partial", r.partial).put("errorCode", r.errorCode).put("errorMessage", r.errorMessage)
            .put("created", r.created).put("updated", r.updated).put("parentId", r.parentId ?: JSONObject.NULL)
            .put("checkpoint", r.checkpoint ?: JSONObject.NULL)
            .put("verifyRequested", r.verifyRequested).put("conversationMode", r.conversationMode)
            .put("replyToId", r.replyToId).put("replyToRole", r.replyToRole).put("replyPreview", r.replyPreview)
            .put("requestMessageId", r.requestMessageId).put("rootContextId", r.rootContextId)
            .put("contextId", r.contextId).put("agentParentId", r.agentParentId ?: JSONObject.NULL)
            .put("agentDepth", r.agentDepth).put("agentTitle", r.agentTitle)
            .put("maxModelCalls", r.maxModelCalls).put("modelCallsUsed", r.modelCallsUsed)
            .put("tokenBudget", r.tokenBudget).put("tokensReserved", r.tokensReserved).put("deadlineAt", r.deadlineAt)
            .put("modelReservations", JSONObject(r.modelReservations)).put("agentCancelled", r.agentCancelled)
            .put("agentTimeoutMillis", r.agentTimeoutMillis).put("agentHistorySteps", JSONArray(r.agentHistorySteps.toList()))
            .put("agentMessages", JSONObject().also { histories -> r.agentMessages.forEach { (key, messages) ->
                histories.put(key, JSONArray(messages.map { message -> JSONObject()
                    .put("role", message.role).put("content", message.content).put("id", message.id)
                    .put("replyToId", message.replyToId).put("replyToRole", message.replyToRole)
                    .put("replyToPreview", message.replyToPreview) }))
            } })
            .also { value -> r.localRoomTurn?.let { turn -> value.put("localRoomTurn", JSONObject()
                .put("roomId", turn.roomId).put("memberIds", JSONArray(turn.memberIds))) } }
    })).toString()

    fun decode(text: String): List<FriendRun> {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "不支持的任务文件版本" }
        val items = json.getJSONArray("runs")
        return (0 until items.length()).map { index ->
            val r = items.getJSONObject(index)
            FriendRun(r.getString("id"), r.getString("resourceId"), r.getString("input"), r.getBoolean("team"),
                r.getString("clientRequestId"), r.getString("state"), r.getString("partial"), r.getString("errorCode"), r.optString("diagnosticId"),
                r.getString("errorMessage"), r.getLong("created"), r.getLong("updated"),
                if (r.isNull("parentId")) null else r.getString("parentId"),
                if (r.isNull("checkpoint")) null else r.getString("checkpoint"), r.optBoolean("verifyRequested"), ConversationMode.requireValid(r.optString("conversationMode", ConversationMode.ASK)),
                r.optJSONObject("localRoomTurn")?.let { LocalRoomTurn(it.getString("roomId"), it.getJSONArray("memberIds").strings()) },
                r.optString("replyToId"), r.optString("replyToRole"), r.optString("replyPreview"),
                requestMessageId = r.optString("requestMessageId"), rootContextId = r.optString("rootContextId"),
                contextId = r.optString("contextId"),
                agentParentId = if (r.isNull("agentParentId")) null else r.getString("agentParentId"),
                agentDepth = r.optInt("agentDepth"), agentTitle = r.optString("agentTitle"),
                maxModelCalls = r.optInt("maxModelCalls", 8), modelCallsUsed = r.optInt("modelCallsUsed"),
                tokenBudget = r.optLong("tokenBudget", 64_000), tokensReserved = r.optLong("tokensReserved"),
                deadlineAt = r.optLong("deadlineAt"),
                agentMessages = r.optJSONObject("agentMessages")?.let { histories -> histories.keys().asSequence().associateWith { key ->
                    histories.getJSONArray(key).objects().map { message -> Message(message.getString("role"), message.getString("content"),
                        message.optString("id"), message.optString("replyToId"), message.optString("replyToRole"), message.optString("replyToPreview")) }
                } }.orEmpty(),
                agentHistorySteps = r.optJSONArray("agentHistorySteps").strings().toSet(),
                modelReservations = r.optJSONObject("modelReservations")?.let { reservations ->
                    reservations.keys().asSequence().associateWith { reservations.getLong(it) }
                }.orEmpty(), agentCancelled = r.optBoolean("agentCancelled"), agentTimeoutMillis = r.optLong("agentTimeoutMillis")).also {
                FriendRunIds.run(it.id); FriendRunIds.resource(it.resourceId); FriendRunIds.request(it.clientRequestId)
                if (it.rootContextId.isNotBlank()) FriendRunIds.run(it.rootContextId)
                require(it.requestMessageId.length <= 160 && it.agentTitle.length <= 80)
                require(it.state in setOf("queued", "running", "interrupted", "completed", "failed", "cancelled", "requires_review"))
                it.parentId?.let(FriendRunIds::run)
                SubagentPolicy.validate(it)
                it.localRoomTurn?.let { turn -> require(turn.roomId == it.resourceId && !it.team) { "群任务目标无效" } }
            }
        }.also { runs ->
            require(runs.map { it.id }.distinct().size == runs.size)
            require(runs.map { it.clientRequestId }.distinct().size == runs.size)
        }
    }
}
