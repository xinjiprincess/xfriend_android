package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalRoomsScreen(vm: FriendViewModel, onModels: () -> Unit, onTasks: () -> Unit) {
    val store = vm.store ?: return
    @Suppress("UNUSED_VARIABLE") val revision = vm.revision
    val rooms = store.localRooms
    var selected by rememberSaveable(vm.accountScope) { mutableStateOf("") }
    var creating by rememberSaveable(vm.accountScope) { mutableStateOf(false) }
    val room = rooms.firstOrNull { it.id == selected }
    LaunchedEffect(vm.requestedLocalRoomId, rooms.map { it.id }) {
        vm.requestedLocalRoomId?.takeIf { id -> rooms.any { it.id == id } }?.let { id ->
            selected = id
            vm.requestedLocalRoomId = null
        }
    }
    BackHandler(enabled = room != null) { selected = "" }
    LaunchedEffect(rooms.map { it.id }) {
        if (selected.isNotBlank() && rooms.none { it.id == selected }) selected = ""
    }
    if (room == null) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
        ) {
            item {
                XfPageHeader("本机群聊", subtitle = "由此手机组织专家协作，使用手机配置的模型服务。")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XfButton("新建群聊", { creating = true }, enabled = !vm.busy, icon = XfIcons.Plus, small = true)
                    XfButton("手机模型", onModels, kind = XfButtonKind.Secondary, small = true)
                    XfButton("任务", onTasks, kind = XfButtonKind.Ghost, small = true)
                }
            }
            item {
                XfBanner(
                    "每群 1–8 位专家。发送给全体或指定一位，每位每次发言一轮；需要联网访问模型服务。",
                    tone = XfTone.Neutral, icon = XfIcons.Users,
                )
            }
            if (rooms.isEmpty()) item {
                EmptyHint("还没有本机群聊", "创建群聊并选择手机中的专家，消息和草稿按当前账号空间保存。")
            }
            items(rooms, key = { it.id }) { current ->
                XfCard(onClick = { selected = current.id }) {
                    XfSectionHeader(current.name, subtitle = "此手机 · ${current.memberIds.size} 位专家")
                    Text(
                        current.messages.lastOrNull()?.content?.take(120) ?: "还没有消息，点击开始协作。",
                        style = XfType.small, color = xf.ink3, maxLines = 3,
                    )
                    if (vm.runList.any { it.localRoomTurn?.roomId == current.id && it.active }) {
                        Spacer(Modifier.height(6.dp))
                        XfStatusChip("正在此手机执行", tone = XfTone.Accent)
                    }
                    if (current.draft.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("有未发送草稿", style = XfType.meta, color = xf.ink3)
                    }
                }
            }
        }
    } else {
        LocalRoomConversation(vm, room, onModels, onTasks) { selected = "" }
    }
    if (creating) LocalRoomCreator(vm, onDismiss = { creating = false }) { name, members ->
        vm.createLocalRoom(name, members)
        creating = false
    }
}

@Composable
private fun LocalRoomCreator(vm: FriendViewModel, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    val store = vm.store ?: return
    val availability = rememberFriendAvailability(store)
    var name by rememberSaveable(vm.accountScope) { mutableStateOf("") }
    var members by rememberSaveable(vm.accountScope) { mutableStateOf(emptyList<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建本机群聊") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                XfTextField(name, { name = it.take(80) }, label = "群聊名称", placeholder = "例如：方案讨论组")
                Text("选择 1–8 位专家（已选 ${members.size}）", style = XfType.small, color = xf.ink2)
                store.experts.forEach { expert ->
                    val chosen = expert.id in members
                    val state = availability[expert.id]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = chosen,
                            onCheckedChange = { checked -> members = if (checked) members + expert.id else members - expert.id },
                            enabled = !vm.busy && state?.remote != true && (chosen || members.size < 8),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(expert.name, style = XfType.bodyStrong, color = xf.ink)
                            Text(state?.label ?: "正在检查配置", style = XfType.meta, color = xf.ink3)
                        }
                    }
                }
                if (store.experts.isEmpty()) Text("请先在工作台创建或导入专家。", style = XfType.small)
                Text("需要电脑引擎的专家仍可通过电脑会话使用。本群只使用手机可执行的专家。", style = XfType.meta, color = xf.ink3)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), members.toList()) },
                enabled = !vm.busy && name.isNotBlank() && members.size in 1..8,
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class LocalRoomExport(val accountScope: String, val generation: Long, val roomId: String, val text: String)

@Composable
private fun LocalRoomConversation(vm: FriendViewModel, room: LocalRoom, onModels: () -> Unit, onTasks: () -> Unit, onBack: () -> Unit) {
    val store = vm.store ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(vm.accountScope, room.id) { mutableStateOf(room.draft) }
    var lastStoredDraft by remember(vm.accountScope, room.id) { mutableStateOf(room.draft) }
    var submittedDraft by rememberSaveable(vm.accountScope, room.id) { mutableStateOf<String?>(null) }
    var submittedAfterRunId by rememberSaveable(vm.accountScope, room.id) { mutableStateOf<String?>(null) }
    var target by rememberSaveable(vm.accountScope, room.id) { mutableStateOf("") }
    var targetNeedsReview by rememberSaveable(vm.accountScope, room.id) { mutableStateOf(false) }
    var deleting by remember(vm.accountScope, room.id) { mutableStateOf(false) }
    var pendingExport by remember(vm.accountScope, room.id) { mutableStateOf<LocalRoomExport?>(null) }
    val run = localRoomVisibleRun(room.id, vm.runList)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null && payload.accountScope == vm.accountScope) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FriendStoreRegistry.withAuthorization(payload.accountScope, payload.generation) {
                        require(vm.store?.localRooms?.any { it.id == payload.roomId } == true) { "群聊已删除" }
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                        output.bufferedWriter().use { it.write(payload.text) }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { vm.error = "群聊导出失败，请重新选择保存位置" }
        }
    }
    LaunchedEffect(room.draft) {
        // Follow accepted-send clearing and task retry without replacing newer local typing.
        if (draft == lastStoredDraft) draft = room.draft
        lastStoredDraft = room.draft
    }
    LaunchedEffect(vm.busy, run?.id, room.draft, submittedDraft, vm.error) {
        val submitted = submittedDraft
        if (submitted != null && !vm.busy) {
            if (run?.id != submittedAfterRunId && run?.input == submitted.trim() && room.draft.isEmpty()) {
                if (draft == submitted) draft = ""
                submittedDraft = null
            } else if (vm.error != null) submittedDraft = null
        }
    }
    LaunchedEffect(vm.requestedLocalRoomTurn, room.id, room.memberIds) {
        vm.requestedLocalRoomTurn?.takeIf { it.roomId == room.id }?.let { requested ->
            val restoredTarget = localRoomReplyTarget(room, requested)
            if (restoredTarget == null) {
                targetNeedsReview = true
                vm.error = "原任务的发言对象已变化，请重新选择全体或一位专家后发送。"
            } else {
                target = restoredTarget
                targetNeedsReview = false
            }
            vm.requestedLocalRoomTurn = null
        }
    }
    LaunchedEffect(room.memberIds) {
        if (target.isNotBlank() && target !in room.memberIds) targetNeedsReview = true
    }
    val targets = if (target.isBlank()) room.memberIds else listOf(target)
    val list = rememberLazyListState()
    val following by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(room.id) { if (room.messages.isNotEmpty()) list.scrollToItem(room.messages.lastIndex) }
    LaunchedEffect(room.messages.size, run?.partial?.length) {
        if (following && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XfSectionHeader(room.name, subtitle = "此手机执行 · ${room.memberIds.size} 位专家", action = "群列表", onAction = onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XfButton("手机模型", onModels, kind = XfButtonKind.Secondary, small = true)
                XfButton("导出记录", {
                    pendingExport = LocalRoomExport(vm.accountScope, FriendStoreRegistry.generation(vm.accountScope), room.id, localRoomMarkdown(room))
                    export.launch("群聊-${room.id}.md")
                }, enabled = room.messages.isNotEmpty(), kind = XfButtonKind.Ghost, small = true)
                XfButton("删除群聊", { deleting = true }, enabled = !vm.busy && run?.active != true, kind = XfButtonKind.Ghost, small = true)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (room.messages.isEmpty()) item {
                EmptyHint("开始本机协作", "给全体专家一个目标，或选择一位专家继续讨论。本群只共享群内消息。")
            }
            items(room.messages, key = { it.id }) { message ->
                XfCard {
                    Text(
                        if (message.role == "user") "你" else message.speakerName ?: "专家",
                        style = XfType.bodyStrong, color = xf.ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (message.role == "user") SelectionContainer { Text(message.content, style = XfType.body, color = xf.ink2) }
                    else SalesMarkdown(message.content)
                }
            }
            if (run != null && run.state != "completed") item {
                XfCard {
                    XfStatusChip(friendRunLabel(run.state), tone = if (run.active) XfTone.Accent else XfTone.Warning)
                    if (run.partial.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("本轮输出（含已完成发言）", style = XfType.meta, color = xf.ink3)
                        Spacer(Modifier.height(6.dp))
                        SalesMarkdown(run.partial)
                    }
                    if (!run.active && run.errorMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(run.errorMessage, style = XfType.small, color = xf.ink3)
                    }
                    Spacer(Modifier.height(8.dp))
                    XfButton("查看任务", onTasks, kind = XfButtonKind.Ghost, small = true)
                    if (run.canVerifyCheckpoint) XfButton(
                        "核查写入并继续", { vm.verifyRun(run) }, enabled = !vm.busy && !vm.executing,
                        kind = XfButtonKind.Secondary, small = true,
                    )
                    if (run.canResume) XfButton(
                        "从已保存进度继续", { vm.resumeRun(run) }, enabled = !vm.busy && !vm.executing,
                        kind = XfButtonKind.Secondary, small = true,
                    )
                }
            }
        }
        if (vm.executing) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = xf.accent)
            Text(
                if (run?.active == true) "此手机正在执行本群任务" else "其他对话正在执行，可先保存群草稿",
                Modifier.weight(1f).padding(start = 8.dp), style = XfType.meta, color = xf.ink3,
            )
            XfButton(if (run?.active == true) "停止" else "任务", if (run?.active == true) vm::cancel else onTasks, kind = XfButtonKind.Ghost, small = true)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (targetNeedsReview) Text("请重新选择本次发言专家。", style = XfType.meta, color = xf.ink3)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { XfChip("全体专家", selected = !targetNeedsReview && target.isBlank(), onClick = { target = ""; targetNeedsReview = false }) }
                items(room.memberIds, key = { it }) { memberId ->
                    XfChip(store.experts.firstOrNull { it.id == memberId }?.name ?: "已移除专家", selected = !targetNeedsReview && target == memberId, onClick = { target = memberId; targetNeedsReview = false })
                }
            }
            ChatComposer(
                draft = draft,
                onDraftChange = { value -> draft = value.take(12000); vm.saveLocalRoomDraft(room.id, draft) },
                placeholder = if (vm.executing) "先写下一条消息…" else "给专家一个共同目标…",
                sendEnabled = !targetNeedsReview && !vm.busy && !vm.testingModel && !vm.executing && draft.isNotBlank() && targets.isNotEmpty(),
                onSend = { submittedDraft = draft; submittedAfterRunId = run?.id; vm.sendLocalRoom(room.id, targets.toList(), draft) },
                hint = "本次一轮 · ${targets.size} 位专家 · 此手机执行",
                onHintAction = null,
                modeLabels = vm.supportedConversationModes.map(::conversationModeLabel),
                modeIndex = vm.supportedConversationModes.indexOf(vm.conversationMode).coerceAtLeast(0),
                onModeSelect = { index ->
                    if (!vm.busy && !vm.executing) vm.supportedConversationModes.getOrNull(index)?.let(vm::saveConversationMode)
                },
                enabled = !vm.busy,
            )
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("删除本机群聊？") },
        text = { Text("将删除此手机当前账号下的群记录和草稿。专家配置、电脑和云群保持原样。") },
        confirmButton = { TextButton(onClick = { vm.deleteLocalRoom(room.id); deleting = false }, enabled = !vm.busy && run?.active != true) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留") } },
    )
}

private fun localRoomMarkdown(room: LocalRoom): String = buildString {
    append("# ").append(room.name).append("\n\n执行位置：此手机\n\n")
    room.messages.forEach { message ->
        append("## ").append(if (message.role == "user") "你" else message.speakerName ?: "专家").append("\n\n")
        append(message.content).append("\n\n")
    }
}
