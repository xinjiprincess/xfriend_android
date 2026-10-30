package com.singu.xfriend

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SalesTask(val id: String = UUID.randomUUID().toString(), val operation: String, val title: String,
    val payload: String, val status: String = "queued", val detail: String = "正在复盘 · 0%", val created: Long = System.currentTimeMillis(), val progress: Int = 0, val recordId: String = "") {
    val active get() = status in listOf("queued", "running")
    fun json() = JSONObject().put("id", id).put("operation", operation).put("title", title).put("payload", payload).put("status", status).put("detail", detail).put("created", created).put("progress", progress).put("recordId", recordId)
    companion object { fun read(j: JSONObject) = SalesTask(j.getString("id"), j.getString("operation"), j.getString("title"), j.getString("payload"), j.getString("status"), j.getString("detail"), j.getLong("created"), j.optInt("progress", 0).coerceIn(0, 100), j.optString("recordId")) }
}
internal fun reviewProgress(percent: Int) = "正在复盘 · ${percent.coerceIn(0, 100)}%"
internal fun mergeCancelledReviewPayload(worker: JSONObject, latest: JSONObject): JSONObject = JSONObject(worker.toString()).apply {
    // Worker checkpoints are newest, while credentials and cancellation decisions belong to the UI/remote cancellation worker.
    if (latest.has("lease")) put("lease", latest.get("lease"))
    listOf("cancelRequested", "remoteCancelConfirmed", "remoteCancelState").forEach { key ->
        if (latest.has(key)) put(key, latest.get(key)) else remove(key)
    }
}
internal fun splitSalesText(text: String): List<String> = text.chunked(8000).ifEmpty { listOf("") }

/** Durable encrypted queue. User initiated data-sync FGS outlives Compose/ViewModels. */
class SalesTaskService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var processing: Job? = null
    @Volatile private var currentId = ""
    @Volatile private var lastStartId = 0
    private val preferences by lazy { getSharedPreferences("sales-processing-scopes", MODE_PRIVATE) }
    private fun scopes() = preferences.getStringSet("scopes", emptySet()).orEmpty().toSet()
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sales-tasks", "录音与销售分析任务", NotificationManager.IMPORTANCE_LOW))
        startForeground(46, notification(0))
        serviceScope.launch {
            for (scope in scopes()) SalesRepository(this@SalesTaskService, scope).changeTasks { tasks -> tasks.map { if (it.status == "running") it.copy(status = "queued", detail = reviewProgress(it.progress)) else it } }
            for (signal in signals) {
                retryRemoteCancellations()
                while (isActive) {
                    val next = scopes().asSequence().mapNotNull { scope -> SalesRepository(this@SalesTaskService, scope).tasks().firstOrNull { it.status == "queued" }?.let { scope to it } }.firstOrNull() ?: break
                    currentId = next.second.id
                    processing = launch { process(next.first, next.second) }
                    processing?.join(); processing = null; currentId = ""
                }
                val completedStart = lastStartId
                val pendingCancel = scopes().any { scope -> SalesRepository(this@SalesTaskService, scope).tasks().any(::needsRemoteCancellation) }
                if (pendingCancel) { delay(15000); signals.trySend(Unit) }
                else if (scopes().none { scope -> SalesRepository(this@SalesTaskService, scope).tasks().any { it.active } }) stopSelfResult(completedStart)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        intent?.getStringExtra("scope")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { scope ->
            preferences.edit().putStringSet("scopes", scopes() + scope).commit()
        }
        val cancelId = intent?.getStringExtra("cancel").orEmpty()
        if (cancelId.isNotBlank() && cancelId == currentId) processing?.cancel()
        signals.trySend(Unit)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
    private fun notification(percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "sales-tasks").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("${ProductBrand.name} · 后台处理")
            .setContentText(reviewProgress(percent)).setProgress(100, percent.coerceIn(0, 100), false).setContentIntent(open).setOngoing(percent < 100).build()
    }
    private fun needsRemoteCancellation(task: SalesTask): Boolean = task.status == "cancelled" && runCatching {
        val payload = JSONObject(task.payload)
        payload.optString("reviewId").isNotBlank() && !payload.optBoolean("remoteCancelConfirmed")
    }.getOrDefault(false)

    private suspend fun retryRemoteCancellations() {
        for (scope in scopes()) {
            val repo = SalesRepository(this, scope)
            for (task in repo.tasks().filter(::needsRemoteCancellation)) cancelRemote(repo, task.id)
        }
    }

    private suspend fun cancelRemote(repo: SalesRepository, id: String) {
        val task = repo.tasks().find { it.id == id && needsRemoteCancellation(it) } ?: return
        val payload = JSONObject(task.payload).put("cancelRequested", true)
        repo.changeTasks { tasks -> tasks.map { current -> if (current.id == id && current.status == "cancelled")
            current.copy(payload = JSONObject(current.payload).put("cancelRequested", true).toString()) else current } }
        val response = runCatching { withTimeout(10000) { SalesApi().trial(payload.getString("trialUrl"), payload.getJSONObject("lease"),
            JSONObject(), reviewPath(payload.getString("reviewId")) + "/cancel") } }.getOrNull() ?: return
        // A final result may have won the race. Keep its immutable remote ID for explicit resume/download;
        // never claim a credit was refunded and never recreate a locally deleted record.
        if (response.optString("state") in listOf("cancelled", "succeeded")) repo.changeTasks { tasks -> tasks.map { current ->
            if (current.id == id && current.status == "cancelled") current.copy(payload = JSONObject(current.payload)
                .put("cancelRequested", false).put("remoteCancelConfirmed", true).put("remoteCancelState", response.getString("state")).toString()) else current
        } }
    }
    private suspend fun process(scope: String, initial: SalesTask) {
        val repo = SalesRepository(this, scope)
        val payload = JSONObject(initial.payload)
        payload.remove("remoteCancelConfirmed"); payload.remove("cancelRequested")
        val job = currentCoroutineContext().job
        val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:sales-task")
        var lastPercent = initial.progress.coerceIn(0, 99)
        fun progress(value: Int) {
            job.ensureActive()
            lastPercent = maxOf(lastPercent, value.coerceIn(0, 99))
            repo.changeTasks { tasks ->
                val current = tasks.find { it.id == initial.id }
                if (current == null || current.status == "cancelled") throw CancellationException("已取消")
                tasks.map { if (it.id == initial.id) it.copy(status = "running", progress = lastPercent,
                    detail = reviewProgress(lastPercent), payload = payload.toString(),
                    recordId = it.recordId.ifBlank { if (initial.operation == "analyze") payload.getJSONObject("item").getString("id") else initial.id }) else it }
            }
            getSystemService(NotificationManager::class.java).notify(46, notification(lastPercent))
        }
        try {
            wake.acquire(6 * 60 * 60 * 1000L)
            if (initial.operation == "analyze") {
                val recordId = payload.getJSONObject("item").getString("id")
                require(repo.load().records.any { it.id == recordId }) { "原记录已删除，任务停止；不会重新创建已删除记录" }
            }
            progress(1)
            if (initial.operation == "analyze") analyze(repo, payload, ::progress)
            else import(repo, initial, payload, ::progress)
            job.ensureActive()
            repo.changeTasks { tasks -> tasks.map { if (it.id == initial.id && it.status != "cancelled") it.copy(status = "done", detail = reviewProgress(100), progress = 100, payload = "{}") else it } }
            if (repo.tasks().any { it.id == initial.id && it.status == "done" }) getSystemService(NotificationManager::class.java).notify(46, notification(100))
        } catch (e: CancellationException) {
            repo.changeTasks { tasks -> tasks.map { if (it.id == initial.id) it.copy(status = if (it.status == "cancelled") "cancelled" else "queued",
                detail = reviewProgress(lastPercent), payload = mergeCancelledReviewPayload(payload, JSONObject(it.payload)).toString()) else it } }
            withContext(NonCancellable) { cancelRemote(repo, initial.id) }
            throw e
        } catch (e: Exception) {
            repo.changeTasks { tasks -> tasks.map { if (it.id == initial.id && it.status != "cancelled") it.copy(status = "failed", detail = e.message ?: "处理未完成", payload = payload.toString()) else it } }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }
    private fun import(repo: SalesRepository, task: SalesTask, p: JSONObject, progress: (Int) -> Unit) {
        if (repo.load().records.any { it.id == task.id }) { progress(99); return }
        val uris = (p.optJSONArray("uris")?.strings()?.takeIf { it.isNotEmpty() } ?: listOf(p.getString("uri"))).distinct().map(Uri::parse)
        val kind = p.getString("kind")
        require(uris.size <= 100 && (kind == "image" || uris.size == 1)) { "单次图片数量超过限制" }
        val name = contentResolver.query(uris.first(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "沟通记录"
        var text = ""
        val files = mutableListOf<java.io.File>()
        try {
            if (kind == "audio") {
                val file = repo.file(task.id + ".wav"); files.add(file)
                decodeAudio(this, uris.single(), file) { progress(3 + it * 60 / MAX_AUDIO_SECONDS) }
            } else if (kind == "image") {
                uris.forEachIndexed { index, uri ->
                    progress(3 + index * 85 / uris.size)
                    val file = repo.file(task.id + (if (index == 0) "" else "-image-$index") + ".jpg")
                    files.add(file); importImage(this, uri, file)
                    files.addAll(reviewImageTiles(file, repo::file) { progress(3 + index * 85 / uris.size) })
                }
            } else {
                require(kind == "text")
                progress(10)
                text = contentResolver.openInputStream(uris.single())!!.use { String(it.bounded(4 * 1024 * 1024), Charsets.UTF_8) }.removePrefix("\uFEFF")
                require(text.isNotBlank() && text.length <= 1_000_000) { "文本为空或超过 100 万字，请按项目拆分" }
            }
            progress(95)
            repo.update { d ->
                val customer = p.optString("customerId").takeIf { id -> d.customers.any { it.id == id } }.orEmpty()
                if (d.records.any { it.id == task.id }) d else d.copy(records = listOf(Communication(task.id, name.take(100), customer, kind, text,
                    file = files.firstOrNull()?.name.orEmpty(), attachments = files.map { it.name })) + d.records)
            }
            progress(99)
        } catch (e: Exception) { if (repo.load().records.none { it.id == task.id }) files.forEach { it.delete() }; throw e }
        finally {
            if (repo.load().records.any { it.id == task.id }) uris.forEach { uri ->
                val otherUsers = repo.tasks().any { it.id != task.id && it.status in listOf("queued", "running", "failed", "cancelled") && runCatching {
                    val body = JSONObject(it.payload)
                    body.optString("uri") == uri.toString() || uri.toString() in body.optJSONArray("uris").strings()
                }.getOrDefault(false) }
                if (!otherUsers) runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }
    private suspend fun analyze(repo: SalesRepository, p: JSONObject, progress: (Int) -> Unit) {
        val api = SalesApi()
        val item = Communication.read(p.getJSONObject("item"))
        val job = currentCoroutineContext().job
        // Never resurrect a deleted record or overwrite user edits with a stale worker snapshot.
        var lastText = p.optString("savedText", item.text)
        val existing = repo.load().records.find { it.id == item.id } ?: error("原记录已删除，任务停止")
        require(existing.text == item.text || existing.text == lastText || existing.text == p.optJSONObject("review")?.optString("corrected")) {
            "原文已在处理期间修改，请基于最新内容重新分析"
        }
        fun save(artifact: ReviewArtifact) {
            job.ensureActive()
            repo.update { d ->
                val current = d.records.find { it.id == item.id } ?: error("原记录已删除，任务停止")
                require(current.customerId == item.customerId) { "记录关联客户已修改，请基于最新关联重新分析" }
                require(current.text == item.text || current.text == lastText || current.text == artifact.text) { "原文已在处理期间修改，请基于最新内容重新分析" }
                d.copy(records = d.records.map { if (it.id == item.id) current.copy(text = artifact.text,
                    rawTranscript = artifact.rawTranscript, report = artifact.report, analysisJson = artifact.analysisJson, mode = p.getString("mode")) else it })
            }
            lastText = artifact.text; p.put("savedText", lastText)
        }
        val artifact = if (p.getBoolean("trial")) ReviewTrial(repo, api, item, p, progress, ::save).run()
            else ReviewProcessor(repo, api, ModelProfile.read(p.getJSONObject("profile")), item, p, progress, ::save).run()
        save(artifact)
        progress(99)
    }
    companion object {
        internal fun kick(context: Context, scope: String, cancel: String = "") {
            context.startForegroundService(Intent(context, SalesTaskService::class.java).putExtra("scope", scope).putExtra("cancel", cancel))
        }
    }
}
