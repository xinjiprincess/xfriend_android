package com.singu.xfriend

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One voice turn's visible state. Recording is always short: tap, speak, stop, edit, send. */
private sealed interface StarVoiceState {
    data object Idle : StarVoiceState
    data class Recording(val startedAt: Long) : StarVoiceState
    data object Transcribing : StarVoiceState
}

private fun starHost(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()

private fun starReadiness(s: Store): String {
    val connection = activeConnection(s)
    return when {
        connection != null && !FriendProviderCatalog.canInfer(connection) -> "当前渠道不支持聊天"
        connection != null && connection.model.isBlank() -> "已选 ${connection.name}，尚未选择模型"
        connection != null -> "当前连接 · ${connection.name}"
        s.activeModelConnectionId.isNotBlank() -> "当前连接已停用，请重新选择"
        s.baseUrl.isBlank() -> "还没有连接中转站"
        s.model.isBlank() -> "已填地址，还没有模型"
        s.apiKey.isBlank() -> "还没有填写凭据"
        else -> "默认中转站 · ${starHost(s.baseUrl).ifBlank { "已配置" }}"
    }
}

@Composable
private fun StarHeader(s: Store, onHistory: () -> Unit, onSettings: () -> Unit, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        XfStarMark(size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("星助理", style = XfType.h3, color = xf.ink)
            Text(starReadiness(s), style = XfType.meta, color = xf.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        XfIconButton(XfIcons.Clock, "历史会话", onClick = onHistory)
        XfIconButton(XfIcons.Settings, "设置", onClick = onSettings)
        XfIconButton(Icons.Outlined.Call, "通话", onClick = onCall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarTray(runs: List<FriendRun>, onOpenTasks: () -> Unit, onView: (FriendRun) -> Unit, onCancel: (FriendRun) -> Unit) {
    val active = subagentTaskRows(runs).filter { subagentTaskGroup(it) != SubagentTaskGroup.Completed }.take(3)
    if (active.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("任务托盘", style = XfType.caps, color = xf.ink3)
            Spacer(Modifier.weight(1f))
            Text("全部任务", style = XfType.meta, color = xf.accentInk, modifier = Modifier.combinedClickable(onClick = onOpenTasks, onLongClick = onOpenTasks))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(active, key = { it.id }) { run ->
                XfCard(Modifier.width(232.dp), onClick = { onView(run) }, padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subagentTaskTitle(run), style = XfType.small, color = xf.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (run.active) XfIconButton(XfIcons.X, "取消任务 ${subagentTaskTitle(run)}", { onCancel(run) }, size = 32.dp)
                    }
                    Text("${if (run.contextId.isBlank()) "主任务" else "子任务 · 深度 ${run.agentDepth}"} · ${subagentTaskStateLabel(run)}",
                        style = XfType.meta, color = xf.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subagentTaskBudget(run, runs).takeIf { it.isNotBlank() }?.let {
                        Text(it, style = XfType.meta, color = xf.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StarQuote(msg: Message, messages: List<Message>) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(xf.surface2).padding(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(2.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(xf.accentLine))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(if (msg.replyToRole == "assistant") "回复 星助理" else "回复 我", style = XfType.label, color = xf.accentInk)
            Text(starQuotePreview(msg.replyToId, msg.replyToPreview, messages), style = XfType.meta, color = xf.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarUserMessage(msg: Message, messages: List<Message>, onReply: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 20.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (msg.replyToId.isNotBlank()) StarQuote(msg, messages)
        Box {
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).background(xf.accentSolid).combinedClickable(onClick = {}, onLongClick = { menu = true }).padding(horizontal = 12.dp, vertical = 9.dp),
            ) { Text(msg.content, style = XfType.body, color = xf.surface) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("回复这条", style = XfType.body) }, onClick = { menu = false; onReply() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarAssistantMessage(msg: Message, messages: List<Message>, onReply: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
        XfStarMark(size = 24.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("星助理", style = XfType.label, color = xf.ink3)
            if (msg.replyToId.isNotBlank()) StarQuote(msg, messages)
            Box {
                Column(Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })) { SalesMarkdown(msg.content) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("回复这条", style = XfType.body) }, onClick = { menu = false; onReply() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarWelcome(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("打开就是星助理。", style = XfType.h1, color = xf.ink)
        Spacer(Modifier.height(6.dp))
        Text("说清要完成的事，我会安排任务、调用工具，并把结果和依据带回来。", style = XfType.body, color = xf.ink2)
        Spacer(Modifier.height(18.dp))
        val suggests = listOf("把今天的会议录音整理成纪要", "帮我改这份 PPT 的第三页", "查一下这个季度的销售重点", "把这封邮件改得更简短")
        suggests.forEach { text ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(xf.surface)
                    .border(1.dp, xf.line, RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onPick(text) }, onLongClick = { onPick(text) })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = XfType.body, color = xf.ink, modifier = Modifier.weight(1f))
                Icon(XfIcons.ChevRight, contentDescription = null, tint = xf.ink4, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarComposer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onMic: () -> Unit,
    sending: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(xf.surface)
                .border(1.dp, xf.line, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = XfType.body.copy(color = xf.ink),
                cursorBrush = SolidColor(xf.accentSolid),
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.weight(1f).heightIn(min = 34.dp).padding(vertical = 6.dp),
                decorationBox = { inner ->
                    if (value.isBlank()) Text("和星助理说点什么…", style = XfType.body, color = xf.ink4)
                    inner()
                },
            )
            if (value.isBlank()) XfIconButton(XfIcons.Mic, "语音输入", onClick = onMic, enabled = enabled, size = 38.dp)
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (sendEnabled) xf.accentSolid else xf.surface3)
                    .combinedClickable(enabled = sendEnabled, onClick = onSend, onLongClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = xf.surface)
                else Icon(XfIcons.ArrowUp, contentDescription = "发送", tint = if (sendEnabled) xf.surface else xf.ink4, modifier = Modifier.size(18.dp))
            }
        }
        Text("文字与语音都会发往已配置的模型服务；结果需要你确认后才会对外发送。", style = XfType.meta, color = xf.ink4, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}

@Composable
private fun StarRecordingBar(elapsed: Long, transcribing: Boolean, onStop: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(xf.surface)
            .border(1.dp, xf.accentLine, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(xf.red))
        Spacer(Modifier.width(8.dp))
        Text(if (transcribing) "正在转写…" else "录音中 ${"%02d:%02d".format(elapsed / 60, elapsed % 60)}", style = XfType.bodyStrong, color = xf.ink)
        Spacer(Modifier.weight(1f))
        if (!transcribing) {
            XfButton("取消", onCancel, kind = XfButtonKind.Ghost, small = true)
            Spacer(Modifier.width(6.dp))
            XfButton("停止并转写", onStop, small = true)
        } else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = xf.accent)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StarHistorySheet(s: Store, vm: FriendViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = vm.histories.keys.toList()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("历史会话", style = XfType.h2, color = xf.ink)
            Text("会话仍按原有专家/团队保存，入口只保留星助理。", style = XfType.meta, color = xf.ink3)
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) Text("还没有历史会话。", style = XfType.body, color = xf.ink3)
            entries.forEach { id ->
                val target = StarConversation.fromHistoryKey(id)
                val name = (if (target.team) s.teams.firstOrNull { it.id == target.resourceId }?.name
                    else s.experts.firstOrNull { it.id == target.resourceId }?.name) ?: "会话 ${target.resourceId.take(6)}"
                val last = vm.histories[id]?.lastOrNull()?.content?.take(40).orEmpty()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (vm.selected == target.resourceId && vm.teamMode == target.team) xf.accentSoft else xf.surface)
                        .combinedClickable(onClick = {
                            if (vm.selected != target.resourceId || vm.teamMode != target.team) vm.replyTarget = null
                            vm.selected = target.resourceId; vm.teamMode = target.team; onDismiss()
                        }, onLongClick = {})
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = XfType.bodyStrong, color = xf.ink)
                        if (last.isNotBlank()) Text(last, style = XfType.meta, color = xf.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${vm.histories[id]?.size ?: 0} 条", style = XfType.meta, color = xf.ink4)
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StarChatPage(
    s: Store,
    vm: FriendViewModel,
    onHistory: (() -> Unit)? = null,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    onTasks: () -> Unit,
    onTaskDetails: (String) -> Unit = { onTasks() },
    onRooms: () -> Unit = {},
    onRemote: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val recorder = remember { StarVoiceRecorder(context.applicationContext) }
    var showHistory by remember { mutableStateOf(false) }
    var showCall by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf<StarVoiceState>(StarVoiceState.Idle) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    var startRequested by remember { mutableStateOf(false) }

    val historyKey = StarConversation(vm.selected, vm.teamMode).historyKey
    val messages = vm.histories[historyKey].orEmpty()
    val conversationTasks = subagentConversationTasks(vm.runList, vm.selected, vm.teamMode)
    val run = conversationTasks.filter { it.contextId.isBlank() }.maxByOrNull { it.created }
    val agentTasks = subagentTaskRows(conversationTasks.filter { it.contextId.isNotBlank() }).sortedBy { it.created }.takeLast(8)
    val targetRun = vm.targetAgentRunId?.let { id -> vm.runList.firstOrNull { it.id == id && it.contextId.isNotBlank() } }
    // Replies are intentionally delivered only after the full task result is committed.
    val streaming = ""
    val canSend = vm.draft.isNotBlank() && vm.selected.isNotBlank() &&
        (vm.targetAgentRunId == null || targetRun?.let { subagentCanTarget(it) && !it.active } == true)
    LaunchedEffect(vm.selected, vm.teamMode, s.experts.map { it.id }, s.teams.map { it.id }) {
        val valid = if (vm.teamMode) s.teams.any { it.id == vm.selected } else s.experts.any { it.id == vm.selected }
        if (!valid) {
            vm.replyTarget = null
            vm.selected = if (vm.teamMode) s.teams.firstOrNull()?.id.orEmpty() else s.experts.firstOrNull()?.id.orEmpty()
        }
    }

    suspend fun stopAndTranscribe() {
        val file = recorder.stop()
        voice = StarVoiceState.Idle
        if (file == null) { voiceError = "录音太短，请按住说完再停。"; return }
        voice = StarVoiceState.Transcribing
        when (val result = starTranscribe(context.applicationContext, file, s)) {
            is StarTranscribe.Ok -> {
                val text = result.text.trim()
                if (text.isNotBlank()) vm.draft = if (vm.draft.isBlank()) text else vm.draft.trimEnd() + " " + text
            }
            is StarTranscribe.Failed -> voiceError = result.message
        }
        voice = StarVoiceState.Idle
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRequested = true else voiceError = "没有麦克风权限。可继续用文字输入，或到系统设置里允许录音。"
    }
    LaunchedEffect(startRequested) {
        if (!startRequested) return@LaunchedEffect
        startRequested = false
        recorder.start().onSuccess { voice = StarVoiceState.Recording(System.currentTimeMillis())
            if (SmallWindowController.supported(context)) SmallWindowController.request() }
            .onFailure { voiceError = "无法开始录音：${it.message ?: "设备忙"}" }
    }
    LaunchedEffect(voice) {
        val started = (voice as? StarVoiceState.Recording)?.startedAt ?: return@LaunchedEffect
        elapsed = 0
        while (voice is StarVoiceState.Recording) {
            elapsed = (System.currentTimeMillis() - started) / 1000
            if (elapsed >= 60) { stopAndTranscribe(); break }
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    var waitingTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.executing) {
        while (vm.executing) { waitingTick = System.currentTimeMillis(); delay(1000) }
    }
    val waitingLong = vm.runList.any { it.active && waitingTick - it.created >= 10_000 }
    LaunchedEffect(messages.size, streaming.length, run?.state) {
        if (messages.isNotEmpty() || streaming.isNotBlank()) runCatching { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
    }

    Column(Modifier.fillMaxSize()) {
        StarHeader(s, onHistory = { showHistory = true }, onSettings = onSettings, onCall = { showCall = true })
        if (waitingLong) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                XfBanner("正在处理消息…", tone = XfTone.Neutral, icon = XfIcons.Clock)
            }
        }
        if (vm.taskManagerVisible) StarTaskManagerPanel(vm, onOpenAll = onTasks,
            onHide = { vm.applyTaskManagerCommand(TaskManagerCommand.HIDE) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (messages.isEmpty() && streaming.isBlank() && agentTasks.isEmpty()) {
                    item("welcome") {
                        Box(Modifier.fillParentMaxHeight(0.94f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            StarWelcome { vm.draft = it }
                        }
                    }
                }
                itemsIndexed(messages, key = { index, message -> starMessageId(message, index) }) { index, message ->
                    val messageId = starMessageId(message, index)
                    val boundRun = conversationTasks.firstOrNull { task ->
                        task.contextId.isBlank() && (task.requestMessageId == messageId || task.clientRequestId == messageId)
                    }
                    when (message.role) {
                        "user" -> {
                            StarUserMessage(message, messages) { vm.targetAgentRunId = null; vm.replyTarget = StarReply(messageId, "user", message.content.take(140)) }
                            if (boundRun != null) {
                                Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 20.dp), horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    XfStatusChip(label = friendRunLabel(boundRun.state),
                                        tone = if (boundRun.state == "completed") XfTone.Success else if (boundRun.active) XfTone.Accent else XfTone.Warning)
                                    if (boundRun.active) {
                                        Spacer(Modifier.width(8.dp))
                                        XfIconButton(XfIcons.X, "停止此任务", { vm.cancelRun(boundRun) }, size = 30.dp)
                                    }
                                }
                                if (boundRun.errorMessage.isNotBlank() && boundRun.state != "completed") {
                                    Text(boundRun.errorMessage, style = XfType.meta, color = xf.warningText,
                                        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 20.dp))
                                }
                            }
                        }
                        else -> StarAssistantMessage(message, messages) { vm.targetAgentRunId = null; vm.replyTarget = StarReply(messageId, "assistant", message.content.take(140)) }
                    }
                }
                if (streaming.isNotBlank()) {
                    item("stream") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
                            XfStarMark(size = 24.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("星助理", style = XfType.label, color = xf.ink3)
                                Spacer(Modifier.height(4.dp))
                                SalesMarkdown(streaming)
                                Text("▍", style = XfType.body, color = xf.accentInk)
                            }
                        }
                    }
                }
                if (run != null && run.requestMessageId.isBlank() && (run.active || run.state in setOf("interrupted", "requires_review", "failed"))) {
                    item("run") {
                        SubagentTaskCard(run, vm.runList, { onTaskDetails(run.id) }, { vm.cancelRun(run) }, enabled = !vm.busy,
                            modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
                items(agentTasks, key = { "agent:${it.id}" }) { agent ->
                    if (vm.taskManagerVisible) SubagentTaskCard(agent, vm.runList, { onTaskDetails(agent.id) }, { vm.cancelRun(agent) },
                        onContinue = { vm.replyTarget = null; vm.targetAgentRunId = agent.id }, enabled = !vm.busy,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
        (voiceError ?: vm.error)?.let { text ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                XfBanner(text, tone = if (voiceError != null) XfTone.Warning else XfTone.Danger, icon = XfIcons.Bell, action = "知道了",
                    onAction = { voiceError = null; if (vm.error == text) vm.error = null })
            }
        }
        val pendingInbox = vm.inbox.filter { it.runId.isBlank() || it.retryable || it.state in setOf("提交失败", "已中断", "已接收", "提交中") }
        if (pendingInbox.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pendingInbox.takeLast(3).forEach { entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(xf.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        XfStatusChip(label = entry.state, tone = if (entry.retryable) XfTone.Warning else XfTone.Accent)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.input, style = XfType.small, color = xf.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entry.message.isNotBlank()) Text(entry.message, style = XfType.meta, color = xf.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.retryable) XfButton("重试", { vm.retryInbox(entry) }, kind = XfButtonKind.Ghost, small = true)
                        XfIconButton(XfIcons.X, "移除收件记录", { vm.dismissInbox(entry.requestId) }, size = 30.dp)
                    }
                }
            }
        }
        vm.replyTarget?.let { reply ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(xf.surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(xf.accentLine))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (reply.role == "assistant") "回复 星助理" else "回复 我", style = XfType.label, color = xf.accentInk)
                    Text(starQuotePreview(reply.messageId, reply.preview, messages), style = XfType.meta, color = xf.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                XfIconButton(XfIcons.X, "取消引用", onClick = { vm.replyTarget = null }, size = 32.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("发送到 ", style = XfType.meta, color = xf.ink3)
            Text(if (vm.targetAgentRunId == null) "星助理" else targetRun?.let(::subagentTaskTitle) ?: "目标任务暂不可用",
                style = XfType.label, color = xf.accentInk, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (vm.targetAgentRunId != null) XfIconButton(XfIcons.X, "取消发送目标，返回星助理", { vm.targetAgentRunId = null }, size = 32.dp)
        }
        if (targetRun?.state == "requires_review") Text("此任务的操作结果需要先到任务面板核查。", style = XfType.meta,
            color = xf.warningText, modifier = Modifier.padding(horizontal = 20.dp))
        if (targetRun?.active == true) Text("此任务正在执行，结束后可发送补充消息；草稿会保留。", style = XfType.meta,
            color = xf.ink3, modifier = Modifier.padding(horizontal = 20.dp))
        XfSegmented(
            listOf("问答", "计划", "执行"),
            ConversationMode.supported.indexOf(vm.conversationMode).coerceAtLeast(0),
            { index -> if (!vm.busy) vm.setConversationMode(ConversationMode.supported[index]) },
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        when (val state = voice) {
            StarVoiceState.Idle -> StarComposer(
                value = vm.draft,
                onValueChange = { vm.draft = it },
                enabled = !vm.busy,
                sendEnabled = canSend,
                onSend = { vm.send() },
                onMic = {
                    voiceError = null
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRequested = true
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                sending = vm.inbox.any { it.state == "提交中" } && vm.draft.isBlank(),
            )
            is StarVoiceState.Recording -> StarRecordingBar(elapsed, transcribing = false, onStop = { scope.launch { stopAndTranscribe() } }, onCancel = { recorder.cancel(); voice = StarVoiceState.Idle })
            StarVoiceState.Transcribing -> StarRecordingBar(elapsed, transcribing = true, onStop = {}, onCancel = {})
        }
    }
    if (showHistory) StarHistorySheet(s, vm) { showHistory = false }
    if (showCall) StarCallOverlay(vm, s) { showCall = false }
}

@Composable
private fun StarTaskManagerPanel(vm: FriendViewModel, onOpenAll: () -> Unit, onHide: () -> Unit) {
    val filters = listOf("全部", "进行中", "待处理", "已完成", "事项与提醒")
    var filter by remember { mutableStateOf("全部") }
    var page by remember { mutableStateOf(0) }
    // Keep the projection fresh when a running task creates a phone item/reminder.
    LaunchedEffect(vm.runList.size, vm.runList.count { it.state == "completed" }) { vm.refreshPhoneTasks() }
    val filtered = UnifiedTaskProjection.filter(vm.unifiedTasks, filter)
    val pages = ((filtered.size + 9) / 10).coerceAtLeast(1)
    val current = page.coerceIn(0, pages - 1)
    LaunchedEffect(filter, filtered.size) { if (page > pages - 1) page = (pages - 1).coerceAtLeast(0) }
    XfCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("任务管理器", style = XfType.h3, color = xf.ink)
                Text("当前账户 ${filtered.size} 项 · 来源与状态分开保留", style = XfType.meta, color = xf.ink3)
            }
            XfButton("全部任务", onOpenAll, kind = XfButtonKind.Ghost, small = true)
            XfIconButton(XfIcons.X, "收起任务管理器", onHide, size = 30.dp)
        }
        Spacer(Modifier.height(8.dp))
        XfSegmented(filters, filters.indexOf(filter).coerceAtLeast(0), { filter = filters[it]; page = 0 })
        Spacer(Modifier.height(6.dp))
        if (filtered.isEmpty()) Text("当前筛选没有任务。隐藏任务管理器不会取消后台任务。", style = XfType.small, color = xf.ink2)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(UnifiedTaskProjection.page(filtered, current), key = { "${it.source}:${it.id}" }) { task ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(xf.surface2).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = XfType.small, color = xf.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${if (task.source == UnifiedTaskProjection.SOURCE_PHONE) "本机事项" else "执行任务"} · ${unifiedTaskStateLabel(task.state)}" +
                            (if (task.detail.isBlank()) "" else " · ${task.detail}"), style = XfType.meta, color = xf.ink3,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (task.source == UnifiedTaskProjection.SOURCE_RUN && task.active) {
                        vm.runList.firstOrNull { it.id == task.id }?.let { run ->
                            XfIconButton(XfIcons.X, "停止任务 ${task.title}", { vm.cancelRun(run) }, size = 28.dp)
                        }
                    }
                }
            }
        }
        if (pages > 1) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                XfButton("上一页", { page = (current - 1).coerceAtLeast(0) }, enabled = current > 0, kind = XfButtonKind.Ghost, small = true)
                Text("${current + 1} / $pages", style = XfType.meta, color = xf.ink3)
                XfButton("下一页", { page = (current + 1).coerceAtMost(pages - 1) }, enabled = current < pages - 1, kind = XfButtonKind.Ghost, small = true)
            }
        }
    }
}

private fun unifiedTaskStateLabel(state: String): String = when (state) {
    "queued" -> "排队中"; "running" -> "执行中"; "completed" -> "已完成"; "open" -> "待处理"
    "scheduled" -> "已安排提醒"; "requires_review" -> "待核查"; "interrupted" -> "已中断"
    "failed" -> "失败"; "cancelled" -> "已停止"; "missed" -> "已错过"; else -> state
}

@Composable
private fun StarField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String, secret: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = XfType.label, color = xf.ink2)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp)).background(xf.surface2)
                .border(1.dp, xf.line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            if (value.isBlank()) Text(placeholder, style = XfType.small, color = xf.ink4)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = (if (secret) XfType.mono else XfType.small).copy(color = xf.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false) else KeyboardOptions.Default,
                cursorBrush = SolidColor(xf.accentSolid), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun StarSettingsPage(
    s: Store,
    vm: FriendViewModel,
    onModels: () -> Unit,
    onTasks: () -> Unit,
    onRooms: () -> Unit,
    onRemote: () -> Unit,
    onPhoneTools: () -> Unit = {},
    onMemory: () -> Unit = {},
) {
    var asrUrl by remember { mutableStateOf(s.asrUrl) }
    var asrModel by remember { mutableStateOf(s.asrModel) }
    var asrKey by remember { mutableStateOf(s.asrKey) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("模型、语音与回退都在这里；密钥只保存在本机安全存储。", style = XfType.small, color = xf.ink3)
        }
        item {
            XfCard {
                Text("模型与连接", style = XfType.h3, color = xf.ink)
                Spacer(Modifier.height(4.dp))
                Text("${starReadiness(s)} · ${(activeConnection(s)?.model ?: s.model).ifBlank { "未填模型" }}", style = XfType.meta, color = xf.ink3)
                Spacer(Modifier.height(10.dp))
                XfButton("供应商与账号管理", onModels, kind = XfButtonKind.Secondary, small = true)
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("消息提示音", style = XfType.h3, color = xf.ink)
                        Text("发送成功和任务完成时播放简短本机提示音；默认关闭。", style = XfType.meta, color = xf.ink3)
                    }
                    Switch(checked = s.messageSounds, onCheckedChange = { on -> vm.change { it.messageSounds = on } })
                }
            }
        }
        item {
            XfCard {
                Text("手机能力", style = XfType.h3, color = xf.ink)
                Text("设置闹钟通知、保存文件、创建事项与定时提醒；普通请求默认执行，不需要先切换模式。开启无障碍后可控制当前界面。", style = XfType.meta, color = xf.ink3)
                Spacer(Modifier.height(10.dp))
                XfButton("手机能力与权限", onPhoneTools, kind = XfButtonKind.Secondary, small = true)
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语音识别", style = XfType.h3, color = xf.ink)
                    Spacer(Modifier.weight(1f))
                    XfStatusChip(if (s.asrUrl.isBlank()) "未配置" else "已配置", tone = if (s.asrUrl.isBlank()) XfTone.Warning else XfTone.Success)
                }
                Text("语音先转成可编辑文字，再由你点发送。转写服务与聊天模型相互独立；未配置时文字输入照常可用。", style = XfType.meta, color = xf.ink3)
                Spacer(Modifier.height(10.dp))
                StarField(asrUrl, { asrUrl = it }, "ASR 地址", "https://…/v1")
                Spacer(Modifier.height(8.dp))
                StarField(asrModel, { asrModel = it }, "ASR 模型", "whisper-1 或手写模型名")
                Spacer(Modifier.height(8.dp))
                StarField(asrKey, { asrKey = it }, "ASR 凭据（可留空复用旧版 API 密钥）", "sk-…", secret = true)
                Spacer(Modifier.height(10.dp))
                XfButton("保存语音配置", {
                    vm.change { st -> st.asrUrl = asrUrl.trim(); st.asrModel = asrModel.trim(); st.asrKey = asrKey.trim() }
                }, small = true)
                Spacer(Modifier.height(6.dp))
                Text("原始录音只作临时转写，结束即删除；不会写入会话或长期记忆。", style = XfType.meta, color = xf.ink4)
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("长期记忆", style = XfType.h3, color = xf.ink)
                        Text("查看、删除或重新整理星助理记住的偏好、个人标签和重要事项。", style = XfType.meta, color = xf.ink3)
                    }
                    XfButton("查看", onMemory, kind = XfButtonKind.Secondary, small = true)
                }
            }
        }
        item {
            XfCard {
                Text("任务与子代理", style = XfType.h3, color = xf.ink)
                Text("长任务在后台运行；返回应用时可从任务中心查看子代理、进度和取消。", style = XfType.meta, color = xf.ink3)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XfButton("任务中心", onTasks, kind = XfButtonKind.Secondary, small = true)
                    XfButton("本机群聊", onRooms, kind = XfButtonKind.Ghost, small = true)
                    XfButton("电脑与平台", onRemote, kind = XfButtonKind.Ghost, small = true)
                }
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("星助理单入口", style = XfType.h3, color = xf.ink)
                        Text("关闭后回到原来的四个页签；会话、任务与配置都不会删除。", style = XfType.meta, color = xf.ink3)
                    }
                    Switch(checked = s.starUi, onCheckedChange = { on -> vm.change { it.starUi = on } })
                }
            }
        }
        item {
            XfCard {
                Text("数据与日志", style = XfType.h3, color = xf.ink)
                Text("日志只记录请求编号、协议、模型与错误类别，不写入提示词、回复、录音或凭据。导出前会先脱敏。", style = XfType.meta, color = xf.ink3)
            }
        }
    }
}
