package com.singu.xfriend

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先恢复本机外观偏好（主题模式 / 主色），再进入 Compose，避免首帧闪色。
        XfAppearance.initialize(this)
        setContent {
            ProductTheme {
                LaunchedEffect(Unit) {
                    SmallWindowController.requests.collect {
                        delay(180)
                        SmallWindowController.enter(this@MainActivity)
                    }
                }
                StartupPermissionGate { UnifiedRoot() }
            }
        }
    }
}

class FriendViewModel(application: Application, val accountScope: String) : AndroidViewModel(application) {
    private var authorizationGeneration = FriendStoreRegistry.generation(accountScope)
    private fun checkAuthorization(generation: Long = authorizationGeneration) {
        if (!FriendStoreRegistry.authorized(accountScope, generation)) throw CancellationException("账号授权已结束")
    }
    private var conversationModeState by mutableStateOf(ConversationMode.defaultFor(BuildConfig.SALES_APP))
    val conversationMode: String get() = conversationModeState
    // Phone-only capability list; remote composers must use their negotiated capabilities.
    val supportedConversationModes: List<String> get() = ConversationMode.supported
    fun setConversationMode(mode: String): Unit = saveConversationMode(mode)
    fun saveConversationMode(mode: String) {
        if (mode !in supportedConversationModes) { error = "不支持的会话模式"; return }
        change { it.conversationMode = mode }
    }
    var store by mutableStateOf<Store?>(null)
        private set
    var revision by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var executing by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    /** Immediate receipts for every accepted input, independent of model/runtime timing. */
    internal var inbox by mutableStateOf<List<InboxEntry>>(emptyList())
        private set
    /** Complete task manager starts hidden; only the current user's explicit command changes it. */
    var taskManagerVisible by mutableStateOf(false)
        private set
    var taskManagerNotice by mutableStateOf("")
        private set
    internal var phoneItems by mutableStateOf<List<org.json.JSONObject>>(emptyList())
        private set
    internal val unifiedTasks: List<UnifiedTask> get() = UnifiedTaskProjection.merge(runList, phoneItems)
    private var selectedState by mutableStateOf("")
    var selected: String
        get() = selectedState
        set(value) {
            if (selectedState != value) { replyTarget = null; targetAgentRunId = null }
            selectedState = value
        }
    private var teamModeState by mutableStateOf(false)
    var teamMode: Boolean
        get() = teamModeState
        set(value) {
            if (teamModeState != value) { replyTarget = null; targetAgentRunId = null }
            teamModeState = value
        }
    var draft by mutableStateOf("")
    /** Message-level reply target for the next send; cleared once the turn is accepted. */
    var replyTarget by mutableStateOf<StarReply?>(null)
    private var targetAgentState by mutableStateOf<String?>(null)
    var targetAgentRunId: String?
        get() = targetAgentState
        set(value) {
            if (targetAgentState != value) replyTarget = null
            targetAgentState = value
        }
    var credentialDraft by mutableStateOf<String?>(null)
    var histories by mutableStateOf<Map<String, List<Message>>>(emptyMap())
    var requestedLocalRoomId by mutableStateOf<String?>(null)
    var requestedLocalRoomTurn by mutableStateOf<LocalRoomTurn?>(null)
    private val localRoomDraftSaves = mutableMapOf<String, Job>()
    private fun refresh() {
        store?.let { s -> histories = synchronized(s) { conversationModeState = s.conversationMode; s.messages.toMap() } }
        revision++
    }
    private suspend fun persist(s: Store, generation: Long, action: (Store) -> Unit) {
        withContext(Dispatchers.IO) {
            FriendStoreRegistry.withAuthorization(accountScope, generation) {
                synchronized(s) {
                    val experts = s.experts; val teams = s.teams; val skills = s.skills
                    val knowledge = s.knowledge; val messages = s.messages
                    val url = s.baseUrl; val model = s.model; val key = s.apiKey
                    val tools = s.modelUseTools; val mode = s.conversationMode
                    val messageSounds = s.messageSounds
                    val memoryFacts = s.memoryFacts; val memoryStatus = s.memoryStatus
                    val memoryTurns = s.memoryTurns; val memoryLastAttemptAt = s.memoryLastAttemptAt
                    val localRooms = s.localRooms
                    val connections = s.modelConnections; val activeConnectionId = s.activeModelConnectionId
                    try { action(s); s.save() }
                    catch (e: Exception) {
                        s.experts = experts; s.teams = teams; s.skills = skills
                        s.knowledge = knowledge; s.messages = messages
                        s.baseUrl = url; s.model = model; s.apiKey = key
                        s.modelUseTools = tools; s.conversationMode = mode
                        s.messageSounds = messageSounds
                        s.memoryFacts = memoryFacts; s.memoryStatus = memoryStatus
                        s.memoryTurns = memoryTurns; s.memoryLastAttemptAt = memoryLastAttemptAt
                        s.localRooms = localRooms
                        s.modelConnections = connections; s.activeModelConnectionId = activeConnectionId
                        throw e
                    }
                }
            }
        }
    }
    private var task: Job? = null
    internal var runList by mutableStateOf<List<FriendRun>>(emptyList())
        private set
    var modelTestResult by mutableStateOf("")
        private set
    /** Short, user-facing status of the last save/probe; shown on the model settings page. */
    var modelNotice by mutableStateOf("")
        private set
    var testingModel by mutableStateOf(false)
        private set
    internal var lastDiagnostic by mutableStateOf("")
        private set
    internal val visibleRun: FriendRun? get() = runList.filter { it.contextId.isBlank() && it.resourceId == selected && it.team == teamMode }.maxByOrNull { it.created }
    private val runRepository by lazy { FriendRunRepository(getApplication(), accountScope) }
    private fun reportFailure(failure: Throwable, baseUrl: String = store?.baseUrl.orEmpty()) {
        val problem = friendModelFailure(failure)
        error = problem.userMessage + "（诊断编号：${problem.diagnosticId}）"
        val origin = runCatching { java.net.URI(baseUrl) }.getOrNull()
        lastDiagnostic = org.json.JSONObject().put("version", BuildConfig.VERSION_NAME)
            .put("time", java.time.Instant.now().toString()).put("code", problem.code)
            .put("diagnostic_id", problem.diagnosticId).put("host", origin?.host.orEmpty())
            .put("target", "phone").toString(2)
    }
    fun testModel(url: String, model: String, key: String, useTools: Boolean) {
        if (testingModel || busy || providerBusy) { modelNotice = "正在处理上一个请求，请稍后再测试"; return }
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
        Log.i("XFriendModel", "test start host=$host model=$model hasKey=${key.isNotBlank()} tools=$useTools")
        testingModel = true; modelTestResult = ""; error = null
        modelNotice = if (host.isBlank()) "正在发送测试请求…" else "正在向 $host 发送测试请求…"
        task = viewModelScope.launch {
            try {
                modelTestResult = FriendModelClient.testConnection(url, model, key, useTools)
                modelNotice = ""
                Log.i("XFriendModel", "test ok: ${modelTestResult.take(80)}")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                reportFailure(failure, url)
                modelNotice = error.orEmpty()
                Log.w("XFriendModel", "test failed: ${friendModelFailure(failure).code}")
            } finally { testingModel = false; task = null }
        }
    }
    fun saveModel(url: String, model: String, key: String, useTools: Boolean) {
        val normalized = try { normalizeFriendModelBaseUrl(url).also { if (model.isBlank()) throw FriendModelFailure("CONFIG_MODEL", "请填写模型名称") } }
            catch (failure: Exception) { reportFailure(failure, url); modelNotice = error.orEmpty(); return }
        val s = store ?: return
        if (busy || providerBusy) { modelNotice = "上一个操作还在进行，请稍后再保存"; return }
        val generation = authorizationGeneration
        val nextModel = model.trim(); val nextKey = key.trim()
        busy = true; error = null; modelNotice = "正在保存配置…"
        task = viewModelScope.launch {
            try {
                persist(s, generation) { it.baseUrl = normalized; it.model = nextModel; it.apiKey = nextKey; it.modelUseTools = useTools; it.activeModelConnectionId = "" }
                checkAuthorization(generation); refresh()
                modelNotice = "已保存到本机安全存储：$normalized · $nextModel。新配置只影响后续请求。"
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { modelNotice = ""; reportFailure(e, normalized) }
            finally { busy = false; task = null }
        }
    }
    private val providerController by lazy {
        FriendProviderController(accountScope, viewModelScope, { store }, { authorizationGeneration },
            { !busy && !testingModel }, { refresh() })
    }
    val providerBusy: Boolean get() = providerController.busy
    val providerNotice: String get() = providerController.notice
    val providerError: String? get() = providerController.error
    val providerModels: List<String> get() = providerController.models
    val providerLogin: ProviderDeviceLogin? get() = providerController.login
    fun saveProviderConnection(connection: FriendModelConnection) = providerController.save(connection)
    fun selectProviderConnection(id: String) = providerController.select(id)
    fun deleteProviderConnection(id: String) = providerController.delete(id)
    fun testProviderConnection(connection: FriendModelConnection) = providerController.test(connection)
    fun fetchProviderModels(connection: FriendModelConnection) = providerController.fetchModels(connection)
    fun startProviderLogin(providerType: String) = providerController.startLogin(providerType)
    fun pollProviderLogin() = providerController.pollLogin()
    fun cancelProviderLogin() = providerController.cancelLogin()
    fun importProviderDocument(providerType: String, text: String) = providerController.importDocument(providerType, text)
    fun fetchProviderQuota(connection: FriendModelConnection) = providerController.quota(connection)
    override fun onCleared() { providerController.close(); super.onCleared() }

    private var observation: Job? = null
    private val soundedCompleted = mutableSetOf<String>()
    private var soundBaselineReady = false
    init { observeRuns() }
    internal fun stopObservingRuns() { observation?.cancel(); observation = null }
    internal fun observeRuns() {
        if (observation?.isActive == true) return
        val nextGeneration = FriendStoreRegistry.generation(accountScope)
        if (nextGeneration != authorizationGeneration) {
            store = null; histories = emptyMap(); runList = emptyList(); executing = false
            inbox = emptyList(); phoneItems = emptyList(); taskManagerVisible = false
            soundedCompleted.clear(); soundBaselineReady = false
        }
        authorizationGeneration = nextGeneration
        val generation = authorizationGeneration
        observation = viewModelScope.launch {
            try {
                store = withContext(Dispatchers.IO) { FriendStoreRegistry.get(getApplication(), accountScope) }
                checkAuthorization(generation)
                store?.takeIf { it.migratedLegacyMode }?.let { loaded ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            FriendStoreRegistry.withAuthorization(accountScope, generation) {
                                synchronized(loaded) { loaded.save(); loaded.migratedLegacyMode = false }
                            }
                        }
                    }
                }
                selected = store?.experts?.firstOrNull()?.id.orEmpty(); refresh()
                withContext(Dispatchers.IO) { FriendRunService.recoverStale(getApplication(), accountScope) }
                val queued = withContext(Dispatchers.IO) { runRepository.runs().filter { it.state == "queued" }.map { it.id } }
                checkAuthorization(generation)
                if (queued.isNotEmpty()) {
                    try { FriendRunService.kick(getApplication(), accountScope) }
                    catch (_: Exception) {
                        withContext(Dispatchers.IO) {
                            FriendStoreRegistry.withAuthorization(accountScope, generation) { queued.forEach(runRepository::startFailed) }
                        }
                    }
                }
                while (isActive) {
                    checkAuthorization(generation)
                    val snapshot = withContext(Dispatchers.IO) { runRepository.runs() }
                    checkAuthorization(generation)
                    executing = snapshot.any { it.active }
                    if (snapshot != runList) {
                        val completed = snapshot.filter { it.state == "completed" && it.contextId.isBlank() }
                        if (!soundBaselineReady) {
                            soundedCompleted.addAll(completed.map { it.id }); soundBaselineReady = true
                        } else {
                            val fresh = completed.filter { it.id !in soundedCompleted }
                            if (fresh.isNotEmpty()) {
                                soundedCompleted.addAll(fresh.map { it.id })
                                if (store?.messageSounds == true) AppSounds.reply()
                            }
                        }
                        runList = snapshot
                        inbox = SubmissionInbox.mergeRuns(inbox, snapshot)
                        val latest = snapshot.lastOrNull { it.active } ?: snapshot.maxByOrNull { it.created }
                        if (latest != null && latest.errorCode.isNotBlank()) {
                            lastDiagnostic = org.json.JSONObject().put("version", BuildConfig.VERSION_NAME)
                                .put("run_id", latest.id).put("time", java.time.Instant.ofEpochMilli(latest.updated).toString())
                                .put("code", latest.errorCode).put("diagnostic_id", latest.diagnosticId)
                                .put("target", "phone").put("state", latest.state).toString(2)
                        }
                        status = latest?.let { run -> when (run.state) {
                            "queued" -> "等待执行"; "running" -> "正在执行"; "completed" -> "已完成"
                            "interrupted" -> "进度已保留，可重新发起"; "cancelled" -> "已停止"; else -> "未完成，请查看任务"
                        } }.orEmpty()
                        refresh()
                    }
                    delay(500)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = "本地数据加载失败：${e.message}" }
        }
    }
    fun change(action: (Store) -> Unit) {
        val s = store ?: return
        if (busy || providerBusy) return
        val generation = authorizationGeneration
        busy = true
        viewModelScope.launch {
            try { error = null; persist(s, generation, action); checkAuthorization(generation); refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = "保存失败：${e.message}" }
            finally { busy = false }
        }
    }
    fun send(): String? = sendInput(draft, clearDraft = true, allowLocalCommands = true)

    /** Used by the call overlay: the transcript is not allowed to overwrite the chat draft. */
    internal fun sendFromCall(text: String): String? = sendInput(text, clearDraft = false, allowLocalCommands = false)

    private fun sendInput(raw: String, clearDraft: Boolean, allowLocalCommands: Boolean): String? {
        val s = store ?: return null
        if (raw.isBlank()) return null
        if (selected.isBlank()) { error = "请先选择助手；输入已保留。"; return null }
        val input = raw.trim()
        if (allowLocalCommands) {
            // A restricted task-manager event is local UI control, not a model task. Only this
            // current input can trigger it; quoted text/tool output never reaches this branch.
            TaskManagerIntent.parse(input)?.let { command ->
                if (clearDraft && draft.trim() == input) draft = ""
                applyTaskManagerCommand(command)
                return null
            }
            if (SmallWindowIntent.matches(input)) {
                if (clearDraft && draft.trim() == input) draft = ""
                if (SmallWindowController.supported(getApplication())) {
                    SmallWindowController.request()
                    status = "正在进入小窗模式；录音时也会自动进入小窗。"
                } else status = "此设备或系统不支持画中画小窗。"
                return null
            }
        }
        val id = selected; val isTeam = teamMode
        val mode = ConversationMode.effectiveForRequest(conversationMode, input)
        val agentTarget = targetAgentRunId
        val target = agentTarget?.let { requested -> runList.firstOrNull { it.id == requested && it.contextId.isNotBlank() } }
        if (agentTarget != null && target == null) { error = "目标子任务已不可用，草稿已保留；请选择发送目标。"; return null }
        if (target != null && (target.active || !subagentCanTarget(target))) {
            error = "目标子任务仍在运行或需要核查；草稿已保留。"; return null
        }
        val reply = replyTarget?.takeIf { it.messageId.isNotBlank() }
        val requestId = UUID.randomUUID().toString()
        val historyKey = StarConversation(if (target != null) target.resourceId else id, if (target != null) false else isTeam).historyKey
        // Freeze the visible conversation before this request; concurrent roots never share a live transcript.
        val snapshot = if (target == null) histories[historyKey].orEmpty().toList() else emptyList()
        val entry = InboxEntry(requestId, input, target?.resourceId ?: id, if (target == null) isTeam else false,
            state = "已接收", message = "正在检查运行条件…")
        inbox = SubmissionInbox.upsert(inbox, entry)
        // Clear only this exact draft; text typed for the next request is never discarded.
        if (clearDraft && draft.trim() == input) draft = ""
        error = null
        status = "已接收，正在提交…"
        val generation = authorizationGeneration
        viewModelScope.launch {
            submitInbox(entry, s, generation, target?.id, mode, reply, snapshot)
        }
        return requestId
    }

    private suspend fun submitInbox(entry: InboxEntry, s: Store, generation: Long, targetId: String?,
                                    mode: String, reply: StarReply?, historySnapshot: List<Message>) {
        inbox = SubmissionInbox.upsert(inbox, entry.copy(state = "提交中", message = "正在检查模型与工具条件…"))
        var submitted: FriendRun? = null
        try {
            withContext(Dispatchers.IO) {
                val target = targetId?.let { id -> runList.firstOrNull { it.id == id } }
                AgentRuntime(s).preflight(target?.resourceId ?: entry.resourceId, target == null && entry.team)
                FriendStoreRegistry.withAuthorization(accountScope, generation) {
                    submitted = if (targetId != null) {
                        runRepository.followUpAgent(targetId, entry.input, entry.requestId)
                    } else {
                        runRepository.enqueue(entry.resourceId, entry.input, entry.team, entry.requestId, mode,
                            reply = reply, requestMessageId = entry.requestId, historySnapshot = historySnapshot)
                    }
                }
            }
            checkAuthorization(generation)
            if (targetId == null) {
                persist(s, generation) { st ->
                    val key = StarConversation(entry.resourceId, entry.team).historyKey
                    val previous = st.messages
                    val rows = previous[key].orEmpty()
                    if (rows.none { it.id == entry.requestId }) {
                        st.messages = previous + (key to (rows + Message("user", entry.input, entry.requestId,
                            reply?.messageId.orEmpty(), reply?.role.orEmpty(), reply?.preview.orEmpty())).takeLast(200))
                    }
                }
            }
            FriendRunService.kick(getApplication(), accountScope)
            if (s.messageSounds) AppSounds.sent()
            if (reply != null && replyTarget == reply) replyTarget = null
            if (targetId != null && targetAgentRunId == targetId) targetAgentRunId = submitted?.id
            val runId = submitted?.id.orEmpty()
            inbox = SubmissionInbox.upsert(inbox, entry.copy(state = "已提交", runId = runId,
                message = "任务已提交，可继续发送下一条。"))
            executing = true
            status = "任务已提交；不同请求会按资源并发或排队，可继续输入"
            refresh()
            runList = withContext(Dispatchers.IO) { runRepository.runs() }
            if (taskManagerVisible) refreshPhoneTasks()
        } catch (cancel: CancellationException) {
            submitted?.let { run -> withContext(NonCancellable + Dispatchers.IO) { runRepository.cancel(run.id) } }
            inbox = SubmissionInbox.upsert(inbox, entry.copy(state = "已中断", message = "账号授权已结束；输入仍保留。", retryable = true))
            throw cancel
        } catch (failure: Exception) {
            submitted?.let { run -> withContext(Dispatchers.IO) { runRepository.startFailed(run.id) } }
            val problem = friendModelFailure(failure)
            error = problem.userMessage + "（诊断编号：${problem.diagnosticId}）"
            inbox = SubmissionInbox.upsert(inbox, entry.copy(state = "提交失败",
                message = problem.userMessage + "（" + problem.code + "）", retryable = true))
            status = "提交失败；输入已保留在收件箱，可重试"
        }
    }

    /** Retry uses the preserved input and a new request id; it never overwrites the current draft. */
    internal fun retryInbox(entry: InboxEntry) {
        val s = store ?: return
        if (!entry.retryable || entry.input.isBlank()) return
        val requestId = UUID.randomUUID().toString()
        val next = entry.copy(requestId = requestId, state = "已接收", message = "正在重新提交…", runId = "", retryable = false)
        inbox = SubmissionInbox.upsert(inbox, next)
        val snapshot = histories[StarConversation(entry.resourceId, entry.team).historyKey].orEmpty().toList()
        val generation = authorizationGeneration
        viewModelScope.launch { submitInbox(next, s, generation, null, conversationMode, null, snapshot) }
    }

    internal fun dismissInbox(requestId: String) {
        inbox = inbox.filterNot { it.requestId == requestId }
    }

    internal fun applyTaskManagerCommand(command: TaskManagerCommand) {
        taskManagerVisible = when (command) {
            TaskManagerCommand.SHOW -> true
            TaskManagerCommand.HIDE -> false
            TaskManagerCommand.TOGGLE -> !taskManagerVisible
        }
        taskManagerNotice = if (taskManagerVisible) "任务管理器已显示；隐藏不会取消后台任务。" else "任务管理器已收起；后台任务继续运行。"
        status = taskManagerNotice
        if (taskManagerVisible) refreshPhoneTasks()
    }

    internal fun refreshMemory() { refresh() }

    fun consolidateMemoryNow() {
        val s = store ?: return
        val generation = authorizationGeneration
        val expertId = selected
        val historyKey = StarConversation(selected, teamMode).historyKey
        viewModelScope.launch {
            try {
                status = "正在整理长期记忆…"
                withContext(Dispatchers.IO) { MemoryWorker.consolidateNow(s, accountScope, generation, expertId, historyKey) }
                checkAuthorization(generation)
                refresh()
                status = s.memoryStatus.ifBlank { "记忆整理完成" }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = "记忆整理未完成：${friendModelFailure(failure).userMessage}" }
        }
    }

    internal fun refreshPhoneTasks() {
        val generation = authorizationGeneration
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { PhoneRepository(getApplication(), accountScope).list() }.getOrDefault(emptyList())
            }
            if (authorizationGeneration == generation) phoneItems = items
        }
    }
    fun createLocalRoom(name: String, memberIds: List<String>) {
        val members = memberIds.toList()
        change { s ->
            require(members.all { id -> s.experts.any { it.id == id } }) { "群成员已不存在，请重新选择" }
            val room = LocalRoom("room-${UUID.randomUUID()}", name.trim(), members)
            s.localRooms = s.localRooms + room
        }
    }
    fun saveLocalRoomDraft(roomId: String, draft: String) {
        val s = store ?: return
        if (draft.length > 12000) return
        val generation = authorizationGeneration
        try {
            FriendStoreRegistry.withAuthorization(accountScope, generation) {
                synchronized(s) { s.localRooms = s.localRooms.map { if (it.id == roomId) it.copy(draft = draft) else it } }
            }
        } catch (_: CancellationException) { return }
        revision++
        localRoomDraftSaves.remove(roomId)?.cancel()
        localRoomDraftSaves[roomId] = viewModelScope.launch {
            delay(250)
            try {
                withContext(Dispatchers.IO) {
                    FriendStoreRegistry.withAuthorization(accountScope, generation) { synchronized(s) { s.save() } }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "群草稿未能保存，请保留当前文字并检查设备存储。" }
        }
    }
    fun deleteLocalRoom(roomId: String) {
        change { s ->
            require(runRepository.runs().none { it.active && it.localRoomTurn?.roomId == roomId }) { "请先停止此群的任务" }
            s.localRooms = s.localRooms.filterNot { it.id == roomId }
        }
    }
    fun sendLocalRoom(roomId: String, memberIds: List<String>, input: String) {
        val s = store ?: return
        if (busy || testingModel || executing || input.isBlank()) return
        val text = input.trim(); val members = memberIds.toList(); val mode = conversationMode
        val generation = authorizationGeneration
        busy = true; error = null; status = "正在检查本机群成员…"
        task = viewModelScope.launch {
            var submitted: String? = null
            try {
                withContext(Dispatchers.IO) {
                    val turn = LocalRoomTurn(roomId, members)
                    require(text.length <= 12000) { "群消息不能超过 12000 个字符" }
                    AgentRuntime(s).preflightGroup(roomId, members)
                    FriendStoreRegistry.withAuthorization(accountScope, generation) {
                        synchronized(s) {
                            require(s.localRooms.any { it.id == roomId }) { "群已不存在" }
                            runRepository.enqueue(roomId, text, false, UUID.randomUUID().toString(), mode, turn).also { submitted = it.id }
                        }
                    }
                }
                checkAuthorization(generation)
                FriendRunService.kick(getApplication(), accountScope)
                withContext(Dispatchers.IO) {
                    FriendStoreRegistry.withAuthorization(accountScope, generation) {
                        synchronized(s) {
                            val before = s.localRooms
                            s.localRooms = s.localRooms.map { room ->
                                if (room.id == roomId && room.draft.trim() == text) room.copy(draft = "") else room
                            }
                            try { s.save() } catch (failure: Exception) { s.localRooms = before; throw failure }
                        }
                    }
                }
                executing = true; status = "本机群任务已提交，每位选中专家回复一次"
                runList = withContext(Dispatchers.IO) { runRepository.runs() }
                checkAuthorization(generation); refresh()
            } catch (cancel: CancellationException) {
                submitted?.let { withContext(NonCancellable + Dispatchers.IO) { runRepository.cancel(it) } }
                throw cancel
            } catch (failure: Exception) {
                submitted?.let { withContext(Dispatchers.IO) { runRepository.startFailed(it) } }
                reportFailure(failure); status = "请查看任务状态，草稿已保留"
            } finally { busy = false; task = null }
        }
    }
    fun cancel() {
        val target = targetAgentRunId?.let { id -> runList.firstOrNull { it.id == id } } ?: visibleRun
        if (target != null) cancelRun(target)
    }
    fun cancelRun(run: FriendRun) {
        val generation = authorizationGeneration
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cancellation = FriendStoreRegistry.withAuthorization(accountScope, generation) {
                        FriendRunService.cancel(getApplication(), accountScope, run.id)
                    }
                    cancellation.join()
                }
                checkAuthorization(generation)
                status = "已停止任务及其子任务；已完成的操作和结果仍保留"
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { reportFailure(failure) }
        }
    }
    private fun focusRun(run: FriendRun) {
        var root = run
        val visited = mutableSetOf<String>()
        while (root.agentParentId != null && visited.add(root.id)) {
            root = runList.firstOrNull { it.id == root.agentParentId } ?: break
        }
        root.localRoomTurn?.let { requestedLocalRoomTurn = it; requestedLocalRoomId = it.roomId }
            ?: run { selected = root.resourceId; teamMode = root.team }
        targetAgentRunId = run.id.takeIf { run.contextId.isNotBlank() }
    }
    internal fun verifyRun(run: FriendRun) {
        if (busy || !run.canVerifyCheckpoint) return
        val generation = authorizationGeneration
        busy = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { FriendStoreRegistry.withAuthorization(accountScope, generation) { runRepository.verifyAndResume(run.id) } }
                checkAuthorization(generation)
                FriendRunService.kick(getApplication(), accountScope)
                focusRun(run)
                executing = true; status = "正在核查已执行的写入，结果不符时不会重放"
            } catch (cancel: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runRepository.startFailed(run.id) }
                throw cancel
            } catch (failure: Exception) {
                withContext(Dispatchers.IO) { runRepository.startFailed(run.id) }
                reportFailure(failure)
            } finally { busy = false }

        }
    }
    internal fun resumeRun(run: FriendRun) {
        if (busy || !run.canResume) return
        val generation = authorizationGeneration
        busy = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { FriendStoreRegistry.withAuthorization(accountScope, generation) { runRepository.resume(run.id) } }
                checkAuthorization(generation)
                FriendRunService.kick(getApplication(), accountScope)
                focusRun(run)
                executing = true; status = "正在从检查点继续"
            } catch (cancel: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runRepository.startFailed(run.id) }
                throw cancel
            } catch (failure: Exception) {
                withContext(Dispatchers.IO) { runRepository.startFailed(run.id) }
                reportFailure(failure)
            } finally { busy = false }

        }
    }
    internal fun retryRun(run: FriendRun) {
        if (busy) return
        if (run.contextId.isNotBlank()) {
            focusRun(run); draft = run.input; replyTarget = null
            status = "已取回子任务输入；发送会沿用独立上下文，预算与取消限制继续有效"
            return
        }
        targetAgentRunId = null
        run.localRoomTurn?.let { turn ->
            saveLocalRoomDraft(turn.roomId, run.input)
            requestedLocalRoomTurn = turn
            requestedLocalRoomId = turn.roomId
            status = "原记录已保留；请在群内检查后发送，将重新执行并可能重复已完成的操作"
            return
        }
        selected = run.resourceId; teamMode = run.team; draft = run.input
        replyTarget = run.replyToId.takeIf { it.isNotBlank() }?.let { StarReply(it, run.replyToRole, run.replyPreview) }
        status = "原记录已保留；请检查后点击发送，将重新执行并可能重复已完成的操作"
    }
    fun import(uri: Uri, skill: Boolean) {
        if (busy) return
        val generation = authorizationGeneration
        busy = true
        viewModelScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "导入文档.md"
                    require(if (skill) name.endsWith(".md", true) else name.endsWith(".md", true) || name.endsWith(".txt", true)) { if (skill) "技能仅支持 Markdown (.md)" else "知识支持 .txt 和 .md" }
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytesLimited() } ?: error("无法读取文件")
                    name.substringBeforeLast('.') to bytes.toString(Charsets.UTF_8)
                }
                val s = store ?: return@launch
                persist(s, generation) {
                    if (skill) it.skills = it.skills + Skill(UUID.randomUUID().toString(), pair.first, pair.second)
                    else it.knowledge = it.knowledge + Knowledge(UUID.randomUUID().toString(), pair.first, pair.second)
                }
                refresh()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = "导入失败：${e.message}" }
            finally { busy = false }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= 2 * 1024 * 1024) {
        val count = read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    val bytes = output.toByteArray()
    require(bytes.size <= 2 * 1024 * 1024) { "文件不能超过 2 MB" }
    return bytes
}

@Composable
internal fun XfriendApp(vm: FriendViewModel, onConnectDesktop: () -> Unit) {
    AssistantRoot(vm, account = null, onDesktop = onConnectDesktop)
}

@Composable
internal fun CollectionPage(title: String, subtitle: String, action: String, onCreate: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = title,
                subtitle = subtitle,
                actions = { XfButton(action, onCreate, icon = XfIcons.Plus, small = true) },
            )
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

@Composable
internal fun EmptyHint(title: String, subtitle: String) {
    XfCard(padding = 18.dp) {
        XfEmptyState(icon = XfIcons.Inbox, title = title, body = subtitle)
    }
}

@Composable
internal fun EntityCard(name: String, description: String, detail: String, edit: () -> Unit, delete: () -> Unit, enabled: Boolean) {
    XfCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XfIconTile(XfIcons.Sparkles, tone = XfTone.Accent)
            Spacer(Modifier.width(10.dp))
            Text(name, Modifier.weight(1f), style = XfType.h3, color = xf.ink)
            XfIconButton(XfIcons.Edit, "编辑$name", edit, enabled = enabled)
            XfIconButton(XfIcons.X, "删除$name", delete, enabled = enabled, tint = xf.dangerText)
        }
        Spacer(Modifier.height(6.dp))
        Text(description.ifBlank { "暂无内容" }, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = XfType.small, color = xf.ink2)
        Spacer(Modifier.height(10.dp))
        Text(detail, style = XfType.meta, color = xf.accentInk)
    }
}

@Composable
private fun ChoiceRow(name: String, checked: Boolean, toggle: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { toggle() }); Text(name) } }

