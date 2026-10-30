package com.singu.xfriend

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Separate .qa source set only. Synthetic model responses never open an HTTP connection. */
internal suspend fun verifyLocalRoomRuntime(app: Application) {
    check(app.packageName.endsWith(".qa")) { "UI fixture requires the separate QA application" }
    val scope = "f".repeat(64)
    val evidence = File(app.filesDir, "qa-local-room-evidence.json")
    val checks = JSONObject()
    val result = JSONObject().put("synthetic_only", true).put("network_requests", 0)
        .put("checks", checks).put("passed", false)
    val job = coroutineContext[Job] ?: error("Fixture requires a coroutine")
    check(FriendRunLiveness.reserve(scope, job)) { "A local task is already active" }
    try {
        val store = FriendStoreRegistry.get(app, scope)
        val planner = Expert("qa-local-planner", "QA 规划专家", "QA_LOCAL_PLANNER: respond with a clearly labelled synthetic proposal.")
        val reviewer = Expert("qa-local-reviewer", "QA 复核专家", "QA_LOCAL_REVIEWER: respond with a clearly labelled synthetic review.")
        val roomId = "qa-local-room"
        val members = listOf(planner.id, reviewer.id)
        val privateMarker = "QA_PRIVATE_HISTORY_MUST_NOT_APPEAR_IN_GROUP"
        synchronized(store) {
            store.experts = store.experts.filterNot { it.id in members } + listOf(planner, reviewer)
            store.messages = store.messages + members.associateWith { listOf(Message("user", privateMarker)) }
            store.localRooms = store.localRooms.filterNot { it.id == roomId } + LocalRoom(roomId, "QA 本机专家群 · 示例", members)
            store.save()
        }
        val privateBefore = synchronized(store) { store.messages.toMap() }
        val state = object : RuntimeState {
            override val workspaceRoot get() = store.workspaceRoot
            override val experts get() = store.experts
            override val teams get() = store.teams
            override val skills get() = store.skills
            override val knowledge get() = store.knowledge
            override var messages: Map<String, List<Message>>
                get() = store.messages
                set(value) { store.messages = value }
            override var localRooms: List<LocalRoom>
                get() = store.localRooms
                set(value) { store.localRooms = value }
            override val historyLock get() = store
            override val baseUrl = "https://qa.invalid/v1"
            override val model = "qa-synthetic-model"
            override val apiKey = "QA_SYNTHETIC_PLACEHOLDER"
            override val modelUseTools = false
            override val conversationMode = ConversationMode.ASK
            override fun save() = store.save()
        }
        val repository = FriendRunRepository(app, scope)
        val queue = repository.ledger()
        check(queue.runs().none { it.active }) { "The fixture queue must be idle" }
        val calls = mutableMapOf(planner.id to 0, reviewer.id to 0)
        var failedReviewer = false
        var privateHistoryIsolated = true
        val model: suspend (Request) -> JSONObject = { request ->
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            val messages = body.getJSONArray("messages")
            privateHistoryIsolated = privateHistoryIsolated && !body.toString().contains(privateMarker)
            val speaker = if (messages.getJSONObject(0).getString("content").contains("QA_LOCAL_PLANNER")) planner else reviewer
            calls[speaker.id] = calls.getValue(speaker.id) + 1
            val input = messages.getJSONObject(messages.length() - 1).getString("content")
            if (speaker.id == reviewer.id && input.contains("QA_FAIL_RESUME") && !failedReviewer) {
                failedReviewer = true
                throw IOException("QA synthetic interruption")
            }
            val text = "【QA 合成响应 · ${speaker.name}】" + when {
                input.contains("QA_FAIL_RESUME") -> "检查点恢复轮已完成；这是本机运行时夹具结果。"
                input.contains("QA_MENTION") -> "已仅由所选专家继续讨论；这是示例内容。"
                else -> "已参与手机本机协作；未连接真实模型服务。"
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", text))))
        }
        suspend fun execute(run: FriendRun) {
            val checkpoint = FriendRunRuntimeCheckpoint(queue, run.id) { true }
            val runtime = AgentRuntime(state, checkpoint = checkpoint, requestExecutor = model)
            FriendRunWorker(queue, AgentRunExecution(runtime, run.localRoomTurn), { true }).execute(run)
        }
        suspend fun submit(input: String, speakers: List<String>): FriendRun {
            val pending = queue.enqueue(roomId, input, false, UUID.randomUUID().toString(),
                conversationMode = ConversationMode.ASK, localRoomTurn = LocalRoomTurn(roomId, speakers))
            val claimed = queue.claim() ?: error("Synthetic task was not claimable")
            check(claimed.id == pending.id)
            execute(claimed)
            return queue.runs().first { it.id == pending.id }
        }
        val first = submit("QA_FIRST：请两位专家各提出一条建议。", members)
        checks.put("two_experts_complete", first.state == "completed" && calls.values.sum() == 2)
        val mention = submit("QA_MENTION：只请复核专家继续。", listOf(reviewer.id))
        checks.put("mention_only_selected_expert", mention.state == "completed" && calls[planner.id] == 1 && calls[reviewer.id] == 2)
        val interrupted = submit("QA_FAIL_RESUME：请先规划，再复核；验证中途失败后继续。", members)
        checks.put("failure_preserves_completed_speaker", interrupted.state == "failed" && interrupted.canResume && calls[planner.id] == 2)
        queue.resume(interrupted.id)
        val resumed = queue.claim() ?: error("Resume was not claimable")
        check(resumed.id == interrupted.id)
        execute(resumed)
        val final = queue.runs().first { it.id == resumed.id }
        checks.put("resume_without_repeating_completed_speaker", final.state == "completed" && calls[planner.id] == 2 && calls[reviewer.id] == 4)
        val room = synchronized(store) { store.localRooms.first { it.id == roomId } }
        checks.put("public_messages_exactly_once", room.messages.size == 8 && room.messages.count { it.role == "user" } == 3 &&
            room.messages.count { it.speakerId == planner.id } == 2 && room.messages.count { it.speakerId == reviewer.id } == 3 &&
            room.messages.map { it.id }.distinct().size == room.messages.size)
        checks.put("private_histories_isolated", privateHistoryIsolated && store.messages == privateBefore)
        val reloaded = Store(app, scope)
        checks.put("group_survives_store_reload", reloaded.localRooms.first { it.id == roomId } == room)
        checks.put("placeholder_not_persisted", reloaded.apiKey != state.apiKey && !FriendRunCodec.encode(queue.runs()).contains(state.apiKey))
        result.put("call_counts", JSONObject().also { counts -> calls.forEach { (id, count) -> counts.put(id, count) } }).put("group_messages", room.messages.size)
            .put("passed", checks.keys().asSequence().all { checks.getBoolean(it) })
    } catch (cancelled: CancellationException) {
        result.put("error_type", cancelled.javaClass.simpleName)
        throw cancelled
    } catch (failure: Exception) {
        result.put("error_type", failure.javaClass.simpleName)
    } finally {
        evidence.writeText(result.toString(2))
        FriendRunLiveness.remove(scope, job)
    }
}
