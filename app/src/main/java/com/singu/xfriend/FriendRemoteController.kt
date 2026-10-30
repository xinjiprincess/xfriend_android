package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

internal data class FriendRemoteUi(
    val saved: FriendRemoteSaved = FriendRemoteSaved(), val resources: List<FriendRemoteResource> = emptyList(),
    val ready: Boolean = false, val cancelSupported: Boolean = false, val observing: Boolean = false,
    val approvalsSupported: Boolean = false,
    val features: FriendRemoteFeatures = FriendRemoteFeatures(),
    val notice: String = "", val tool: String = "",
)

/** Called on IO by its owner; only a single observation or submit is active at a time. */
internal class FriendRemoteController(private val storage: FriendRemoteStorage, private val client: FriendRemoteClient = FriendRemoteClient()) {
    private val mutable = MutableStateFlow(FriendRemoteUi())
    val state = mutable.asStateFlow()
    private fun requireCurrent(session: FriendRemoteSession) {
        if (mutable.value.saved.session != session || storage.read().session != session) throw CancellationException("Remote pairing changed")
    }
    @Synchronized private fun saveRun(run: FriendRemoteRun, session: FriendRemoteSession) {
        requireCurrent(session)
        require(run.belongsTo(session)) { "旧配对任务仅保留只读记录，请在对应电脑核对" }
        val next = storage.updateForSession(session) { it.withRun(run) } ?: throw CancellationException("Remote pairing changed")
        mutable.value = mutable.value.copy(saved = next)
    }
    suspend fun restore() {
        guarded {
            val saved = storage.read()
            val restored = saved.session?.let(saved::forSession) ?: saved
            if (restored != saved) storage.write(restored)
            mutable.value = mutable.value.copy(saved = restored)
            if (mutable.value.saved.session != null) reconnect()
        }
    }
    suspend fun pair(address: String, code: String) = guarded {
        val session = client.pair(address, code)
        synchronized(this) {
            val previous = storage.read()
            val oldSession = previous.session
            val saved = if (oldSession == null) previous.forSession(session).also(storage::write)
                else storage.updateForSession(oldSession) { it.forSession(session) } ?: throw CancellationException("Remote pairing changed")
            mutable.value = mutable.value.copy(saved = saved, ready = false, resources = emptyList(), features = FriendRemoteFeatures(), observing = false, tool = "")
        }
        reconnect()
    }
    suspend fun reconnect() = guarded {
        val session = mutable.value.saved.session ?: return@guarded
        mutable.value = mutable.value.copy(ready = false)
        val features = client.capabilities(session)
        val resources = client.resources(session)
        requireCurrent(session)
        mutable.value = mutable.value.copy(ready = true, cancelSupported = features.cancel, approvalsSupported = features.approvals, features = features, resources = resources, notice = "")
        val run = mutable.value.saved.run
        if (run != null && run.runId.isNotEmpty()) observe()
        else if (run != null) mutable.value = mutable.value.copy(notice = "上次提交尚未确认，点击重试提交会复用原任务编号")
    }
    suspend fun send(resource: FriendRemoteResource, input: String, newConversation: Boolean = false, attachmentsJson: String = "[]", selectedThreadId: String? = null) = guarded {
        require(mutable.value.ready) { "请先连接电脑版" }
        require(input.isNotBlank()) { "请输入任务内容" }
        require(input.length <= 100_000) { "输入过长，请缩短后发送" }
        val session = mutable.value.saved.session ?: return@guarded
        val previous = mutable.value.saved.run
        val thread = if(newConversation) "" else selectedThreadId ?: if (previous?.resourceId == resource.id) previous.threadId else ""
        if(previous!=null && !previous.terminal) require(previous.runId.isNotBlank() && (resource.id != previous.resourceId || newConversation || selectedThreadId?.isNotBlank() == true && selectedThreadId != previous.threadId)) { "当前会话已有任务；可选择另一个已有会话或另开对话" }
        saveRun(FriendRemoteRun(UUID.randomUUID().toString(), resource.id, input, threadId = thread, attachmentsJson = attachmentsJson, sessionScope = friendRemoteSessionScope(session)), session)
        submitPending()
    }
    suspend fun selectRun(run: FriendRemoteRun) = guarded {
        val session = mutable.value.saved.session ?: return@guarded
        require(run.belongsTo(session) || run.sessionScope.isBlank() && run.runId.isNotBlank()) { "旧配对任务仅保留只读记录，请在对应电脑核对" }
        if (run.runId.isNotBlank()) observe(run)
        else saveRun(run, session)
    }
    suspend fun submitPending() = guarded {
        val session = mutable.value.saved.session ?: return@guarded
        val pending = mutable.value.saved.run ?: return@guarded
        requireCurrent(session)
        require(pending.belongsTo(session) || pending.sessionScope.isBlank() && pending.runId.isNotBlank()) { "此待提交请求未绑定当前配对，仅保留只读记录" }
        if (pending.runId.isNotEmpty()) { observe(); return@guarded }
        // This identifier and input were persisted before the first request and survive a lost response.
        val result = client.submit(session, pending)
        val id = result.getString("run_id")
        require(id.isNotBlank()) { "电脑没有返回任务编号" }
        saveRun(pending.copy(runId = id, threadId = result.optString("thread_id"), state = result.getString("state"), lastSyncedAt = System.currentTimeMillis()), session)
        observe()
    }
    private suspend fun observe(candidate: FriendRemoteRun? = null) {
        val session = mutable.value.saved.session ?: return
        val run = candidate ?: mutable.value.saved.run ?: return
        requireCurrent(session)
        require(run.belongsTo(session) || run.sessionScope.isBlank() && run.runId.isNotBlank()) { "旧配对任务仅保留只读记录，请在对应电脑核对" }
        mutable.value = mutable.value.copy(observing = true, notice = "")
        try {
            // Snapshot before replay on every restore; never POST a run that already has an id.
            val snapshot = client.snapshot(session, run.runId)
            val current = friendRemoteSnapshot(run, snapshot).copy(sessionScope = friendRemoteSessionScope(session))
            saveRun(current.copy(lastSyncedAt = System.currentTimeMillis()), session)
            if (!current.terminal) {
                var streamFinished = false
                val observerJob = currentCoroutineContext().job
                client.events(session, current, shouldStop = { streamFinished }) { event ->
                    synchronized(this) {
                    observerJob.ensureActive()
                    requireCurrent(session)
                    val previous = mutable.value.saved.run ?: return@synchronized
                    if (previous.historyKey != current.historyKey) return@synchronized
                    val next = friendRemoteEvent(previous, event)
                    if (next != previous) {
                        saveRun(next.copy(lastSyncedAt = System.currentTimeMillis()), session)
                        val payload = event.optJSONObject("payload")
                        val action = when (event.optString("type")) {
                            "tool_start" -> "电脑正在执行工具：" + payload?.optString("name").orEmpty().take(120)
                            "tool_result" -> "电脑已返回工具结果"
                            else -> mutable.value.tool
                        }
                        mutable.value = mutable.value.copy(tool = action)
                        // A completed state can precede the final done output. Do not close early.
                        streamFinished = event.optString("type") == "done"
                    }
                    }
                }
                requireCurrent(session)
                if (streamFinished && mutable.value.saved.run?.terminal == false) {
                    val latest = mutable.value.saved.run!!
                    val confirmed = client.snapshot(session, latest.runId)
                    saveRun(friendRemoteSnapshot(latest, confirmed).copy(lastSyncedAt = System.currentTimeMillis()), session)
                }
                if (mutable.value.saved.run?.terminal != true) mutable.value = mutable.value.copy(notice = "与电脑的观察连接已断开，任务可能仍在运行。请重连获取进度")
            }
        } finally { if (mutable.value.saved.session == session) mutable.value = mutable.value.copy(observing = false) }
    }
    suspend fun cancel() = guarded {
        val s = mutable.value.saved.session ?: return@guarded
        val r = mutable.value.saved.run ?: return@guarded
        require(r.belongsTo(s)) { "旧配对任务仅保留只读记录，请在对应电脑核对" }
        require(mutable.value.cancelSupported && r.runId.isNotBlank()) { "电脑版尚不支持取消此任务" }
        requireCurrent(s)
        client.cancel(s, r.runId)
        requireCurrent(s)
        mutable.value = mutable.value.copy(notice = "已向电脑请求取消，重连确认最终状态")
    }
    suspend fun decideApproval(expectedId: String, expectedHash: String, decision: String) = guarded {
        require(mutable.value.ready && mutable.value.approvalsSupported) { "电脑版尚未开放手机审批，请在电脑处理" }
        val saved = synchronized(this) { mutable.value.saved }
        val session = saved.session ?: return@guarded
        val run = saved.run ?: return@guarded
        require(run.belongsTo(session)) { "旧配对任务仅保留只读记录，请在对应电脑核对" }
        val approval = run.approval ?: throw FriendRemoteFailure("approval_stale", friendRemoteError("approval_stale"))
        require(run.state == "awaiting_approval" && approval.id == expectedId && approval.summaryHash == expectedHash) { "待确认操作已变化，请重连后重新查看" }
        require(!approval.desktopRequired && decision in approval.allowedDecisions) { "此审批必须在电脑版处理" }
        require(run.approvalRequestId.isEmpty() || run.approvalDecision == decision) { "上次决定尚未确认，请重连或重试原决定" }
        val requestId = run.approvalRequestId.ifEmpty { UUID.randomUUID().toString() }
        val pending = run.copy(approvalRequestId = requestId, approvalDecision = decision)
        saveRun(pending, session) // Persist the exact summary and idempotent decision before any network request.
        val response = client.approval(session, run.runId, approval, decision, requestId)
        saveRun(friendRemoteSnapshot(pending, response).copy(lastSyncedAt = System.currentTimeMillis()), session)
        observe()
    }
    private suspend fun guarded(block: suspend () -> Unit) {
        val expectedSession = mutable.value.saved.session
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (mutable.value.saved.session != expectedSession) return
            val message = when (e) {
                is FriendRemoteFailure -> e.message.orEmpty()
                is UnknownHostException -> "找不到电脑地址，请检查地址与网络"
                is SSLException -> "电脑证书未通过验证，请使用受信任 HTTPS 或 USB 环回连接"
                is SocketTimeoutException -> "电脑暂未响应，任务可能仍在运行，请重连"
                is IllegalArgumentException -> e.message ?: "配置或任务参数不正确"
                else -> "连接电脑失败，任务进度已保留。请确认电脑版和连接桥在线后重连"
            }
            mutable.value = mutable.value.copy(notice = message, observing = false, ready = false)
        }
    }
}
