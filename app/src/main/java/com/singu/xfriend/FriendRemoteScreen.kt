package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable internal fun FriendRemoteScreen(accountScope: String, back: () -> Unit) {
    // Changing account creates a different vault and cancels the prior account's observation.
    key(accountScope) { FriendRemoteAccountScreen(accountScope, back) }
}

@Composable private fun FriendRemoteAccountScreen(accountScope: String, back: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val colors = xf
    val controller = remember(accountScope) { FriendRemoteController(FriendRemoteVault(context, accountScope)) }
    val ui by controller.state.collectAsState()
    val pairedScope = ui.saved.session?.let { remotePairedStorageScope(accountScope, it) }
    val scope = rememberCoroutineScope()
    var observation by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("http://127.0.0.1:8765") }
    var code by remember { mutableStateOf("") }
    var draft by rememberSaveable(pairedScope) { mutableStateOf("") }
    var selected by rememberSaveable(pairedScope) { mutableStateOf("") }
    var cancelBusy by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var approvalConfirm by remember(pairedScope) { mutableStateOf<Pair<FriendRemoteApproval, String>?>(null) }
    var approving by remember { mutableStateOf(false) }
    var newConversation by remember(pairedScope) { mutableStateOf(false) }
    var selectedThread by rememberSaveable(pairedScope) { mutableStateOf("") }
    var attachments by remember(pairedScope) { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var fileNotice by remember(pairedScope) { mutableStateOf("") }
    var resultTarget by remember(pairedScope) { mutableStateOf<Triple<String, String, String>?>(null) }
    var attachmentPickerScope by rememberSaveable { mutableStateOf("") }
    var readOnlyRun by remember { mutableStateOf<FriendRemoteRun?>(null) }
    fun observe(action: suspend () -> Unit) {
        val previous = observation
        previous?.cancel()
        observation = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            busy = true
            try { withContext(Dispatchers.IO) { action() } }
            finally { busy = false }
        }
        observation?.let { FriendRemoteVault.registerObservation(accountScope, it); it.start() }
    }
    LaunchedEffect(controller) { observe { controller.restore() } }
    DisposableEffect(controller) { onDispose { observation?.cancel() } }
    BackHandler(onBack = back)
    val run = ui.saved.run
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val session = ui.saved.session
        val resource = selected
        if (uri != null && session != null && attachmentPickerScope == friendRemoteSessionScope(session) && selected.isNotBlank()) scope.launch {
            busy = true
            try {
                val info = withContext(Dispatchers.IO) {
                    var filename = "attachment"
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) filename = c.getString(0) ?: filename }
                    val temporary = java.io.File.createTempFile("remote-upload-", ".bin", context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(65536); var count = 0L
                            while (true) { val n=input.read(buffer); if(n<0)break; count+=n; require(count<=100L*1024*1024){"单份附件最多100MiB"}; output.write(buffer,0,n) }
                        } }
                        FriendRemoteClient().upload(session, resource, temporary, filename, context.contentResolver.getType(uri) ?: "application/octet-stream")
                    } finally { temporary.delete() }
                }
                if (controller.state.value.saved.session == session && resource == selected) {
                    attachments = attachments + org.json.JSONObject().put("path", info.getString("path")).put("filename", info.getString("filename")).put("media_type", info.getString("media_type"))
                    fileNotice = "已上传 ${info.getString("filename")}，发送任务后才交给专家处理"
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { fileNotice=e.message ?: "附件上传失败" }
            finally { busy=false }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { words -> draft = if (draft.isBlank()) words else "$draft\n$words" }
    }
    LaunchedEffect(selected) { attachments = emptyList(); fileNotice = "" }
    val saveResult = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = resultTarget
        val session = ui.saved.session
        if(uri!=null && target!=null && session!=null && target.first==friendRemoteSessionScope(session)) scope.launch {
            try { withContext(Dispatchers.IO) {
                val temporary=java.io.File.createTempFile("remote-result-", ".bin", context.cacheDir)
                try { FriendRemoteClient().download(session,target.second,target.third,temporary); context.contentResolver.openOutputStream(uri)!!.use { output -> temporary.inputStream().use { it.copyTo(output) } } }
                finally { temporary.delete() }
            }; fileNotice="成果已保存到所选位置" }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { fileNotice=e.message ?: "成果下载失败" }
        }
    }
    LaunchedEffect(run?.approvalJson, run?.state, ui.notice) { approving = false }
    val active = run != null && !run.terminal
    val interactionBusy = busy && !ui.observing
    val canStart = !active || newConversation || run?.resourceId != selected || selectedThread.isNotBlank() && selectedThread != run?.threadId
    LaunchedEffect(selected, pairedScope) { selectedThread = "" }
    LaunchedEffect(run?.runId, run?.threadId) { if(run!=null && run.resourceId==selected && run.threadId.isNotBlank()) selectedThread=run.threadId }
    LaunchedEffect(run?.runId) {
        if(run!=null && run.runId.isNotBlank() && !run.terminal) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            runCatching { RemoteObservationService.watch(context,accountScope) }
        }
    }
    LaunchedEffect(ui.saved.session?.origin) { ui.saved.session?.origin?.let { address = it } }
    LaunchedEffect(ui.resources) {
        if (ui.resources.none { it.id == selected }) selected = ui.resources.firstOrNull()?.id.orEmpty()
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
        ) {
            item {
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfIconTile(
                            icon = XfIcons.Laptop,
                            size = XfDimens.avatarMedium,
                            tone = if (ui.ready) XfTone.Accent else XfTone.Neutral,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (ui.ready) "电脑连接已建立" else "电脑连接待恢复",
                                style = XfType.title,
                                color = colors.ink,
                            )
                            Text(
                                "保持电脑在线；任务在后台持续观察，恢复此页可续聊并查看审批与成果。",
                                style = XfType.meta,
                                color = colors.ink3,
                            )
                        }
                        XfStatusChip(
                            label = if (ui.ready) "已连接" else "待恢复",
                            tone = if (ui.ready) XfTone.Success else XfTone.Warning,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    XfBanner(
                        text = "文本和所选附件按电脑模型与权限执行。默认继续当前会话，可选择另开对话；离开页面不会取消电脑任务。",
                        tone = XfTone.Neutral,
                        icon = XfIcons.Help,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.saved.session != null) {
                            XfButton(
                                text = "重连",
                                onClick = { observe { controller.reconnect() } },
                                kind = XfButtonKind.Secondary,
                                icon = XfIcons.Refresh,
                                small = true,
                            )
                        }
                        XfButton(
                            text = if (settings) "收起连接设置" else "连接设置",
                            onClick = { settings = !settings },
                            kind = XfButtonKind.Ghost,
                            icon = XfIcons.Settings,
                            small = true,
                        )
                    }
                }
            }
            if (ui.saved.session == null || !ui.ready || settings) item {
                XfCard {
                    XfSectionHeader(title = "连接电脑执行桥", subtitle = "使用当前账号对应的电脑版配对码。")
                    Spacer(Modifier.height(10.dp))
                    XfTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = "电脑连接桥地址",
                        placeholder = "http://127.0.0.1:8765",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    XfTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = "电脑配对码",
                        placeholder = "输入完整配对码",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(14.dp))
                    XfButton(
                        text = "配对并连接",
                        onClick = {
                            val submitted = code
                            code = ""
                            settings = false
                            observe { controller.pair(address, submitted) }
                        },
                        enabled = !ui.observing && code.isNotBlank(),
                        loading = ui.observing,
                        icon = XfIcons.Plug,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "网络连接使用受信任 HTTPS；USB 可用 http://127.0.0.1:8765。请使用当前账号对应的电脑版配对码。",
                        style = XfType.meta,
                        color = colors.ink3,
                    )
                }
            }
            if (ui.ready) {
                item {
                    key(pairedScope) {
                    if (ui.features.meetings) {
                        RemoteMeetingCreator(ui.saved.session, accountScope, !interactionBusy, ui.features.models, ui.features.materials)
                        RemoteMeetings(ui.saved.session, accountScope, !interactionBusy, ui.features.materials, ui.features.documentExports)
                    }
                    if (ui.features.rooms) RemoteRooms(ui.saved.session, accountScope, !interactionBusy,
                        ui.features.roomModelSettings && ui.features.models, ui.features.materials, ui.features.documentExports)
                    if (ui.features.taskTrees) RemoteTaskTrees(ui.saved.session, accountScope, !interactionBusy, ui.features.responsibleTasks, ui.features.materials)
                    if (ui.features.memory) RemoteMemoryReview(ui.saved.session, ui.resources, accountScope, !interactionBusy)
                    if (ui.features.diagrams) RemoteDiagramPreview(ui.saved.session, ui.resources, accountScope, !interactionBusy)
                    RemoteThreadChooser(ui.saved.session, selected, !interactionBusy, selectedThread, { id -> selectedThread=id;newConversation=false }, { fileNotice=it })
                    }
                }
                if (ui.saved.history.isNotEmpty()) item {
                    XfSectionHeader(title = "电脑任务记录", subtitle = "其他配对的记录仅可只读；旧版已知任务须验证电脑归属")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ui.saved.history, key = { it.historyKey }) { previous ->
                            val canObserve = ui.saved.session?.let { previous.belongsTo(it) } == true || previous.sessionScope.isBlank() && previous.runId.isNotBlank()
                            XfButton((if (canObserve) "" else "只读 · ") + previous.input.take(24).ifBlank { "电脑任务" }, {
                                if (canObserve) observe { controller.selectRun(previous) } else readOnlyRun = previous
                            }, kind = XfButtonKind.Ghost, small = true)
                        }
                    }
                }
                item {
                    XfSectionHeader(
                        title = "选择智能体、专家或团队",
                        subtitle = if (active) "已有任务继续执行，可切换对象或选择其他会话" else "在电脑上运行所选对象",
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ui.resources, key = { it.id }) { resource ->
                            val onSelectResource: (() -> Unit)? = if (!interactionBusy) ({ selected = resource.id }) else null
                            XfCard(
                                onClick = onSelectResource,
                                modifier = Modifier.width(180.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RemoteExpertAvatar(ui.saved.session, resource.id, resource.name, resource.avatarPath != null, resource.kind == "team")
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        resource.name,
                                        style = XfType.bodyStrong,
                                        color = colors.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected == resource.id) {
                                        Icon(XfIcons.Check, contentDescription = "已选择", modifier = Modifier.size(16.dp), tint = colors.accent)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (resource.kind) { "team" -> "团队"; "expert" -> "专家"; else -> "智能体" },
                                        style = XfType.meta,
                                        color = colors.ink3,
                                    )
                                    if (selected == resource.id) {
                                        Spacer(Modifier.width(8.dp))
                                        XfStatusChip(label = "已选择", tone = XfTone.Accent)
                                    }
                                }
                            }
                        }
                    }
                }
                if (ui.resources.isEmpty()) item {
                    XfCard(padding = 18.dp) {
                        XfEmptyState(icon = XfIcons.Sparkles, title = "电脑上没有可运行资源", body = "请先在电脑版创建或启用智能体。")
                    }
                }
            }
            if (ui.notice.isNotEmpty()) item {
                XfBanner(text = ui.notice, tone = XfTone.Danger, icon = XfIcons.Bell)
            }
            if (run != null) {
                item {
                    XfCard {
                        RunStateHeader(run = run, observing = ui.observing, ready = ui.ready)
                        if (run.lastSyncedAt > 0) {
                            Text(
                                "最后同步：" + java.time.Instant.ofEpochMilli(run.lastSyncedAt).atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + "（北京时间）",
                                style = XfType.meta,
                                color = colors.ink3,
                            )
                        }
                        if (!ui.ready && !run.terminal) {
                            Spacer(Modifier.height(8.dp))
                            XfBanner(
                                text = "连接中断不代表任务失败。重连后将获取电脑上的最新状态。",
                                tone = XfTone.Warning,
                                icon = XfIcons.Refresh,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        XfLabeledText(label = "任务", text = run.input, style = XfType.body)
                        for ((label, path) in remoteArtifactLinks(run.partial)) {
                            XfButton("保存 $label", { ui.saved.session?.let { resultTarget=Triple(friendRemoteSessionScope(it),run.resourceId,path); saveResult.launch(label) } }, kind=XfButtonKind.Secondary, small=true)
                            RemoteShareResult(ui.saved.session,run.resourceId,label,path){fileNotice=it}
                        }
                        if (ui.tool.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(ui.tool, style = XfType.mono, color = colors.ink3)
                        }
                        if (run.partial.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            SalesMarkdown(run.partial)
                        }
                        if (run.error.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            XfBanner(text = run.error, tone = XfTone.Danger, icon = XfIcons.Bell)
                        }
                        if (run.state == "awaiting_approval") {
                            val approval = run.approval
                            Spacer(Modifier.height(10.dp))
                            Hr()
                            Spacer(Modifier.height(10.dp))
                            if (approval == null) {
                                XfBanner(
                                    text = "待确认详情尚未收到，请重连获取；未执行自动批准。",
                                    tone = XfTone.Warning,
                                    icon = XfIcons.Shield,
                                )
                            } else {
                                XfSectionHeader(title = "确认以下电脑操作", subtitle = "请核对具体操作与参数，确认只适用于本次待办操作。")
                                Spacer(Modifier.height(6.dp))
                                Column(
                                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                ) {
                                    SelectionContainer { Text(approval.actions, style = XfType.mono, color = colors.ink2) }
                                }
                                Spacer(Modifier.height(8.dp))
                                XfBanner(
                                    text = "确认仅针对上述操作；后续其他操作仍需单独确认。",
                                    tone = XfTone.Warning,
                                    icon = XfIcons.Shield,
                                )
                                if (!ui.approvalsSupported || approval.desktopRequired || approval.allowedDecisions.isEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    XfBanner(text = "此操作需要在电脑版确认。", tone = XfTone.Info, icon = XfIcons.Laptop)
                                } else {
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (decision in listOf("reject", "approve")) if (decision in approval.allowedDecisions && (run.approvalDecision.isEmpty() || run.approvalDecision == decision)) {
                                            XfButton(
                                                text = (if (run.approvalRequestId.isNotEmpty()) "重试" else "") + if (decision == "approve") "批准此操作" else "拒绝此操作",
                                                onClick = { approvalConfirm = approval to decision },
                                                enabled = !approving,
                                                kind = if (decision == "approve") XfButtonKind.Primary else XfButtonKind.Secondary,
                                                small = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (active && run.runId.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            XfButton(
                                text = "重试提交原任务",
                                onClick = { observe { controller.submitPending() } },
                                enabled = !busy,
                                kind = XfButtonKind.Secondary,
                            )
                        }
                        if (active && run.runId.isNotEmpty() && ui.cancelSupported) {
                            Spacer(Modifier.height(10.dp))
                            XfButton(
                                text = if (cancelBusy) "请求取消中…" else "取消电脑任务",
                                onClick = {
                                    cancelBusy = true
                                    val cancelJob = scope.launch(start = CoroutineStart.LAZY) {
                                        try { withContext(Dispatchers.IO) { controller.cancel() }; observe { controller.reconnect() } }
                                        finally { cancelBusy = false }
                                    }
                                    FriendRemoteVault.registerObservation(accountScope, cancelJob); cancelJob.start()
                                },
                                enabled = !cancelBusy,
                                kind = XfButtonKind.Danger,
                            )
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = XfDimens.pagePadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                XfTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = "交给电脑完成的任务",
                    placeholder = "描述任务，由电脑执行",
                    singleLine = false,
                    minLines = 3,
                )
                XfButton(
                    text = "在电脑运行",
                    onClick = {
                        ui.resources.firstOrNull { it.id == selected }?.let { resource ->
                            val input = draft
                            val files=org.json.JSONArray().also { list -> attachments.forEach { list.put(it) } }.toString()
                            val fresh = newConversation
                            val thread = selectedThread.takeIf{it.isNotBlank()}
                            observe { controller.send(resource, input, fresh, files, thread) }
                            attachments=emptyList(); newConversation=false
                        }
                    },
                    enabled = ui.ready && canStart && !interactionBusy && draft.isNotBlank() && selected.isNotEmpty(),
                    icon = XfIcons.Play,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    XfButton("添加附件", { ui.saved.session?.let { attachmentPickerScope=friendRemoteSessionScope(it); attach.launch("*/*") } }, enabled=ui.ready && canStart && !interactionBusy && selected.isNotEmpty() && attachments.size<16, kind=XfButtonKind.Ghost, small=true)
                    XfButton("语音输入", {
                        try { speech.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说出任务内容，识别后可编辑并手动发送")) }
                        catch (_: android.content.ActivityNotFoundException) { fileNotice = "此设备未提供语音识别服务，可使用输入法语音输入。" }
                    }, enabled=!interactionBusy, kind=XfButtonKind.Ghost, small=true)
                    XfButton(if(newConversation) "已选择新对话" else "另开对话", { newConversation=!newConversation; selectedThread=""; attachments=emptyList() }, enabled=!interactionBusy, kind=XfButtonKind.Ghost, small=true)
                    if(attachments.isNotEmpty()) XfButton("移除附件(${attachments.size})", { attachments=emptyList() }, kind=XfButtonKind.Ghost, small=true)
                }
                key(pairedScope) {
                    RemoteMediaActions(ui.saved.session,selected,ui.ready&&canStart&&!interactionBusy&&selected.isNotBlank()&&attachments.size<16,{ resource,info -> if(resource==selected)attachments=attachments+org.json.JSONObject().put("path",info.getString("path")).put("filename",info.getString("filename")).put("media_type",info.getString("media_type")) },{fileNotice=it})
                }
                if(fileNotice.isNotBlank()) Text(fileNotice,style=XfType.meta,color=colors.ink3)
            }
        }
    }
    readOnlyRun?.let { previous ->
        AlertDialog(onDismissRequest = { readOnlyRun = null }, title = { Text("隔离的电脑任务记录 · 只读") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("此记录未绑定当前配对，不能提交、续聊、取消或审批。原请求编号及已有内容仍保留。", style = XfType.meta)
                SelectionContainer { Text(previous.input + "\n\n" + previous.partial, style = XfType.body) }
            } }, confirmButton = { TextButton(onClick = { readOnlyRun = null }) { Text("关闭") } })
    }
    approvalConfirm?.let { (approval, decision) ->
        XfDialog(
            title = if (decision == "approve") "允许电脑执行这些操作？" else "拒绝这些操作？",
            onDismiss = { approvalConfirm = null },
            confirmLabel = if (decision == "approve") "确认批准" else "确认拒绝",
            onConfirm = {
                approvalConfirm = null; approving = true
                observe {
                    try { controller.decideApproval(approval.id, approval.summaryHash, decision) }
                    finally { withContext(NonCancellable + Dispatchers.Main) { approving = false } }
                }
            },
            dismissLabel = "返回核对",
            danger = decision != "approve",
        ) {
            Text("请核对具体操作与参数。确认只适用于本次待办操作。", style = XfType.small, color = colors.ink2)
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(approval.actions, style = XfType.mono, color = colors.ink) }
            }
        }
    }
}

@Composable private fun RunStateHeader(run: FriendRemoteRun, observing: Boolean, ready: Boolean) {
    val colors = xf
    val short = when (run.state) {
        "completed", "done" -> "已完成"
        "failed" -> "失败"
        "cancelled", "canceled" -> "已取消"
        "interrupted" -> "已中断"
        "awaiting_approval", "waiting_approval", "waiting_for_approval" -> "等待确认"
        "queued" -> "排队中"
        "submitting" -> "提交中"
        else -> if (observing) "执行中" else "等待重连"
    }
    val tone = when (short) {
        "已完成" -> XfTone.Success
        "失败", "已取消" -> XfTone.Danger
        "已中断", "等待确认" -> XfTone.Warning
        else -> XfTone.Accent
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        XfStatusChip(label = short, tone = tone)
        Spacer(Modifier.weight(1f))
        Text(
            when (run.state) {
                "completed", "done" -> "任务完成"
                "failed" -> "任务失败"
                "cancelled", "canceled" -> "任务已取消"
                "interrupted" -> "电脑执行已中断，已有内容已保留"
                "awaiting_approval", "waiting_approval", "waiting_for_approval" -> "任务等待你的确认"
                "queued" -> "电脑正在排队"
                "submitting" -> "正在确认任务提交"
                else -> if (observing) "电脑正在执行" else "已保存任务，等待重连"
            },
            style = XfType.meta,
            color = colors.ink3,
        )
    }
    if (!ready && !run.terminal) {
        Spacer(Modifier.height(4.dp))
    }
}
