package com.singu.xfriend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** Merge only the completed visible turn; failures remain an explicit task card, never a fake answer. */
internal fun mergeRootTurn(store: Store, repo: FriendRunRepository, run: FriendRun) {
    if (run.rootContextId.isBlank() || run.agentParentId != null) return
    val latest = repo.runs().firstOrNull { it.id == run.id } ?: return
    if (latest.state != "completed" || latest.partial.isBlank()) return
    val key = if (latest.team) "team:${latest.resourceId}" else latest.resourceId
    synchronized(store) {
        val previous = store.messages
        val merged = RootTurnMerge.merge(previous[key].orEmpty(), latest)
        if (merged == previous[key].orEmpty()) return
        store.messages = previous + (key to merged)
        try { store.save() } catch (failure: Throwable) { store.messages = previous; throw failure }
    }
}

/** A user-started foreground scheduler. Durable queue claims, not UI lifetimes, own execution. */
class FriendRunService : Service() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scopes = ConcurrentHashMap.newKeySet<String>()
    private val repositories = ConcurrentHashMap<String, FriendRunRepository>()
    @Volatile private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Xfriend 后台任务", NotificationManager.IMPORTANCE_LOW))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Xfriend 正在运行任务").setContentText("可返回应用查看结果")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true)
        if (launch != null) notification.setContentIntent(PendingIntent.getActivity(this, 48, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        startForeground(48, notification.build())
        work.launch {
            for (signal in signals) {
                val start = lastStartId
                for (scope in scopes.toList()) {
                    if (!isActive) break
                    try { schedule(scope) }
                    catch (e: CancellationException) { if (!isActive) throw e }
                    catch (_: Exception) { scopes.remove(scope) }
                }
                // A just-finished coordinator can have queued a child without a new Android start id.
                // Inspect the durable queue and liveness under the same lock before dropping signals.
                val queues = scopes.mapNotNull { scope -> repositories[scope]?.let { scope to it.ledger() } }.toMap()
                if (friendRunSchedulerIdle(queues)) {
                    if (signals.tryReceive().isSuccess) { signals.trySend(Unit); continue }
                    if (stopSelfResult(start)) break
                } else if (queues.keys.none { FriendRunLiveness.active(it) }) signals.trySend(Unit)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val scope = intent?.getStringExtra(EXTRA_SCOPE)
        val id = intent?.getStringExtra(EXTRA_ID)
        val valid = scope != null && runCatching { FriendRunIds.scope(scope) }.isSuccess
        when {
            !valid -> stopSelfResult(startId)
            intent?.action == ACTION_KICK && id == null -> { scopes.add(scope!!); signals.trySend(Unit) }
            intent?.action == ACTION_CANCEL && id != null && runCatching { FriendRunIds.run(id) }.isSuccess -> {
                cancel(this, scope!!, id); signals.trySend(Unit)
            }
            intent?.action == ACTION_CANCEL_SCOPE && id == null -> {
                cancelScope(this, scope!!); scopes.remove(scope); signals.trySend(Unit)
            }
            else -> stopSelfResult(startId)
        }
        // Process restart requires an opened app and a fresh authorization check.
        return START_NOT_STICKY
    }

    private fun schedule(scope: String) {
        val repo = repositories.getOrPut(scope) { FriendRunRepository(this, scope) }
        val generation = FriendStoreRegistry.generation(scope)
        if (!FriendStoreRegistry.authorized(scope, generation)) {
            repo.cancelAll(); FriendRunLiveness.jobs(scope).forEach { it.cancel() }; scopes.remove(scope); return
        }
        // Retry durable stale recovery if a previous completion could not commit its state.
        repo.recoverInterrupted()
        while (work.isActive) {
            synchronized(FriendRunLiveness.lock) {
                if (FriendRunLiveness.jobs(scope).size >= 3) return
                val run = repo.claimConcurrent() ?: return
                val job = work.launch(start = CoroutineStart.LAZY) { executeClaimed(scope, generation, repo, run) }
                if (!FriendRunLiveness.reserveRun(scope, run.id, job)) {
                    job.cancel()
                    repo.ledger().finish(run.id, run.partial, "interrupted", "scheduler_busy", "执行名额已占用，请稍后继续。")
                    return
                }
                job.invokeOnCompletion {
                    // This callback runs even if cancellation prevented body entry. Disk IO belongs
                    // to the independent control scope so Service.onDestroy cannot cancel cleanup.
                    controls.launch {
                        runCatching { completeFriendRun(repo.ledger(), scope, run.id, job) { signals.trySend(Unit) } }
                    }
                }
                job.start()
            }
        }
    }

    private suspend fun executeClaimed(scope: String, generation: Long, repo: FriendRunRepository, run: FriendRun) {
        val currentJob = currentCoroutineContext()[Job]!!
        fun authorized() = FriendStoreRegistry.authorized(scope, generation)
        val watcher = work.launch {
            while (isActive) {
                if (!authorized() || repo.runs().none { it.id == run.id && it.state == "running" && !it.agentCancelled }) {
                    currentJob.cancel(); break
                }
                delay(150)
            }
        }
        try {
            val store = FriendStoreRegistry.get(this@FriendRunService, scope)
            val checkpoint = FriendRunRuntimeCheckpoint(repo.ledger(), run.id, ::authorized)
            val base = StoreRuntimeState(store)
            val runtimeState: RuntimeState = when {
                run.contextId.isNotBlank() -> IsolatedAgentState(base, run) { messages, steps ->
                    FriendStoreRegistry.withAuthorization(scope, generation) { repo.saveAgentContext(run.id, messages, steps) }
                }
                run.rootContextId.isNotBlank() -> IsolatedRootState(base, run) { messages, steps ->
                    FriendStoreRegistry.withAuthorization(scope, generation) { repo.saveRootContext(run.id, messages, steps) }
                }
                else -> base
            }
            // The chat UI waits for the complete answer; provider SSE is still consumed internally
            // where a protocol requires it, but deltas are not surfaced as partial messages.
            val runtime = AgentRuntime(runtimeState, checkpoint = checkpoint, conversationMode = run.conversationMode,
                requestStream = false, emitDeltas = false)
            runtime.pendingReply = run.replyToId.takeIf { it.isNotBlank() }?.let { StarReply(it, run.replyToRole, run.replyPreview) }
            val providerSession = FriendProviderSession(scope, generation, store)
            runtime.resolveProviderConnection = { selected -> providerSession.resolve(selected) }
            runtime.phoneTools = PhoneToolRuntime(applicationContext, scope) { authorized() && run.conversationMode == ConversationMode.CRAFT }
            if (!BuildConfig.SALES_APP) runtime.subagents = PersistentSubagentAdapter(repo.ledger(), run.id, ::authorized) { signals.trySend(Unit) }
            FriendRunWorker(repo.ledger(), AgentRunExecution(runtime, run.localRoomTurn), ::authorized).execute(run)
            runCatching { mergeRootTurn(store, repo, run) }
            runCatching {
                val latest = repo.runs().firstOrNull { it.id == run.id } ?: run
                if (latest.state == "completed" && run.rootContextId.isNotBlank()) {
                    controls.launch { runCatching { MemoryWorker.afterRun(store, scope, generation, latest) } }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Fixed text only: neither storage errors nor API headers are logged or persisted.
            runCatching {
                val saved = repo.runs().first { it.id == run.id }
                val review = friendCheckpointNeedsReview(saved.checkpoint)
                repo.ledger().finish(run.id, saved.partial, if (review) "requires_review" else "failed",
                    if (review) "tool_effect_uncertain" else "storage_failed",
                    if (review) "无法确认已执行的工具结果，请先核查，不能自动重放。" else "无法读取或保存本地任务数据，请检查设备存储。")
            }
        } finally { watcher.cancel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { signals.close(); work.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "friend-runs"
        private const val EXTRA_SCOPE = "scope"
        private const val EXTRA_ID = "runId"
        private const val ACTION_KICK = "com.singu.xfriend.FRIEND_RUN_KICK"
        private const val ACTION_CANCEL = "com.singu.xfriend.FRIEND_RUN_CANCEL"
        private const val ACTION_CANCEL_SCOPE = "com.singu.xfriend.FRIEND_RUN_CANCEL_SCOPE"
        private val controls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun kick(context: Context, scope: String) {
            FriendRunIds.scope(scope)
            context.applicationContext.startForegroundService(Intent(context.applicationContext, FriendRunService::class.java)
                .setAction(ACTION_KICK).putExtra(EXTRA_SCOPE, scope))
        }
        fun cancel(context: Context, scope: String, id: String): Job {
            FriendRunIds.scope(scope); FriendRunIds.run(id)
            return controls.launch {
                val repo = FriendRunRepository(context.applicationContext, scope)
                repo.cancel(id)
                // The durable graph cancellation fences descendants before their network jobs stop.
                repo.runs().filter { !it.active }.forEach { FriendRunLiveness.job(scope, it.id)?.cancel() }
            }
        }
        fun cancelScope(context: Context, scope: String): Job {
            FriendRunIds.scope(scope)
            FriendStoreRegistry.revoke(scope)
            val generation = FriendStoreRegistry.generation(scope)
            FriendRunLiveness.jobs(scope).forEach { it.cancel() }
            FriendRunRepository(context.applicationContext, scope).cancelAll()
            return controls.launch {
                try { FriendRunLiveness.jobs(scope).forEach { it.cancelAndJoin() } }
                finally { FriendStoreRegistry.clearIfGeneration(scope, generation) }
            }
        }
        fun recoverStale(context: Context, scope: String) {
            FriendRunIds.scope(scope)
            FriendRunRepository(context.applicationContext, scope).recoverInterrupted()
        }
        fun isActive(scope: String): Boolean { FriendRunIds.scope(scope); return FriendRunLiveness.active(scope) }
    }
}
