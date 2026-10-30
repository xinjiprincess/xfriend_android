package com.singu.xfriend

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChatPage(s: Store, vm: FriendViewModel, onModels: () -> Unit = {}, onRemote: () -> Unit = {}, onTasks: () -> Unit = {}, onRooms: () -> Unit = {}, onCloud: () -> Unit = {}) {
    val colors = xf
    var choosingExpert by remember(vm.accountScope) { mutableStateOf(false) }
    var browsingTeams by remember(vm.accountScope) { mutableStateOf(vm.teamMode) }
    val choices = if (vm.teamMode) s.teams.map { it.id to it.name } else s.experts.map { it.id to it.name }
    LaunchedEffect(choices, vm.teamMode) { if (choices.none { it.first == vm.selected }) vm.selected = choices.firstOrNull()?.first.orEmpty() }
    val history = vm.histories[if (vm.teamMode) "team:${vm.selected}" else vm.selected].orEmpty()
    val run = vm.visibleRun
    val availability = rememberFriendAvailability(s)
    val readiness = availability[if(vm.teamMode) "team:${vm.selected}" else vm.selected] ?: FriendAvailability()
    var choosingLocation by remember { mutableStateOf(false) }
    var choosingRecent by remember { mutableStateOf(false) }
    val selectedName = choices.firstOrNull { it.first == vm.selected }?.second ?: "选择助手"
    val recent = vm.histories.entries.filter { it.value.isNotEmpty() }.sortedByDescending { entry ->
        vm.runList.filter { (if(it.team) "team:${it.resourceId}" else it.resourceId) == entry.key }.maxOfOrNull { it.created } ?: 0L
    }.take(3)
    val showSetup = !readiness.ready && !readiness.remote && readiness.reason.isNotBlank()
    val headerCount = if(showSetup) 1 else 0
    val showWelcome = history.isEmpty() && run == null
    val showRun = run != null && run.state != "completed"
    val welcomeCount = if (showWelcome) 4 + (if (recent.isNotEmpty()) 1 + recent.size else 0) else 0
    val itemCount = headerCount + welcomeCount + history.size + (if(showRun) 1 else 0)
    val list = rememberLazyListState()
    val following by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(vm.selected, vm.teamMode, history.size, headerCount, showRun) {
        val target = when {
            showRun -> headerCount + history.size
            history.isNotEmpty() -> headerCount + history.lastIndex
            else -> 0
        }
        if (itemCount > 0) {
            withFrameNanos { }
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it == itemCount }
            list.scrollToItem(target.coerceIn(0, itemCount - 1))
        }
    }
    LaunchedEffect(run?.id, run?.partial?.length, headerCount, showRun) {
        if (following && showRun) {
            withFrameNanos { }
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it == itemCount }
            list.scrollToItem((headerCount + history.size).coerceIn(0, itemCount - 1))
        }
    }
    if (choosingRecent) {
        ModalBottomSheet(onDismissRequest = { choosingRecent = false }, containerColor = colors.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = XfDimens.pagePadding).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("最近对话", style = XfType.h2, color = colors.ink)
                Text("按最近使用排序，最多显示 3 个会话。", style = XfType.meta, color = colors.ink3)
                if(recent.isEmpty()) {
                    Text("还没有对话记录。", style = XfType.small, color = colors.ink3)
                }
                recent.forEach { entry ->
                    val isTeam = entry.key.startsWith("team:")
                    val id = entry.key.removePrefix("team:")
                    val name = if(isTeam) s.teams.firstOrNull { it.id == id }?.name else s.experts.firstOrNull { it.id == id }?.name
                    if (name != null) {
                        XfCard(padding = 4.dp) {
                            XfListRow(
                                title = name,
                                subtitle = entry.value.last().content,
                                leading = {
                                    XfAvatar(
                                        icon = if (isTeam) XfIcons.Users else XfIcons.Brand,
                                        size = XfDimens.avatarSmall,
                                    )
                                },
                                trailing = {
                                    Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                                },
                                onClick = { vm.teamMode = isTeam; vm.selected = id; choosingRecent = false },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (choosingLocation) {
        ModalBottomSheet(onDismissRequest = { choosingLocation = false }, containerColor = colors.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = XfDimens.pagePadding).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("选择执行位置", style = XfType.h2, color = colors.ink)
                Text("本页对话在此手机执行，也可进入本机群聊、电脑会话或云协作。", style = XfType.small, color = colors.ink2)
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfIconTile(XfIcons.Fingerprint, size = XfDimens.avatarMedium)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("此手机 · 当前执行端", style = XfType.bodyStrong, color = colors.ink)
                            Text("知识检索与工作区工具，模型由配置的 API 提供。", style = XfType.meta, color = colors.ink3)
                        }
                        XfStatusChip(label = "当前", tone = XfTone.Accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if(readiness.ready) "模型配置已齐备；联网状态以实际请求为准。" else readiness.reason.ifBlank { readiness.label },
                        style = XfType.meta,
                        color = colors.ink3,
                    )
                }
                XfButton(
                    text = "此手机 · 专家群聊",
                    onClick = { choosingLocation = false; onRooms() },
                    kind = XfButtonKind.Secondary,
                    icon = XfIcons.Users,
                    modifier = Modifier.fillMaxWidth(),
                )
                XfButton(
                    text = "连接我的电脑",
                    onClick = { choosingLocation = false; onRemote() },
                    kind = XfButtonKind.Secondary,
                    icon = XfIcons.Laptop,
                    modifier = Modifier.fillMaxWidth(),
                )
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfIconTile(XfIcons.Globe, size = XfDimens.avatarMedium, tone = XfTone.Neutral)
                        Spacer(Modifier.width(10.dp))
                        Text("云协作", style = XfType.bodyStrong, color = colors.ink, modifier = Modifier.weight(1f))
                        XfStatusChip(label = "独立连接", tone = XfTone.Neutral)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("打开已有云群与协作入口，执行位置和可用能力由所连接服务确认。", style = XfType.meta, color = colors.ink3)
                    Spacer(Modifier.height(8.dp))
                    XfButton("打开云协作", { choosingLocation = false; onCloud() }, kind = XfButtonKind.Ghost, small = true)
                }
                XfButton(
                    text = "继续使用此手机",
                    onClick = { choosingLocation = false },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("任务失败时不会自动切换执行位置。", style = XfType.meta, color = colors.ink3)
            }
        }
    }
    if (choosingExpert) {
        val sheetChoices = if (browsingTeams) s.teams.map { it.id to it.name } else s.experts.map { it.id to it.name }
        ModalBottomSheet(onDismissRequest = { choosingExpert = false }, containerColor = colors.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = XfDimens.pagePadding).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("选择对话对象", style = XfType.h2, color = colors.ink)
                XfSegmented(
                    options = listOf("专家", "团队"),
                    selectedIndex = if (browsingTeams) 1 else 0,
                    onSelect = { browsingTeams = it == 1 },
                )
                if (sheetChoices.isEmpty()) {
                    Text(
                        if (browsingTeams) "还没有团队，请先在专家页面创建。" else "还没有专家，请先创建或导入专家。",
                        style = XfType.small,
                        color = colors.ink3,
                    )
                }
                sheetChoices.forEach { (id, name) ->
                    val selected = vm.teamMode == browsingTeams && vm.selected == id
                    XfCard(padding = 4.dp) {
                        XfListRow(
                            title = name,
                            subtitle = if (browsingTeams) "${s.teams.firstOrNull { it.id == id }?.members?.size ?: 0} 位成员" else "专家对话",
                            leading = {
                                XfAvatar(
                                    icon = if (browsingTeams) XfIcons.Users else XfIcons.Brand,
                                    size = XfDimens.avatarSmall,
                                    tone = if (selected) XfTone.Accent else XfTone.Neutral,
                                )
                            },
                            trailing = {
                                if (selected) {
                                    Icon(XfIcons.Check, "当前选择", Modifier.size(18.dp), tint = colors.accent)
                                } else {
                                    Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                                }
                            },
                            onClick = { vm.teamMode = browsingTeams; vm.selected = id; choosingExpert = false },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                XfButton(
                    text = "连接电脑执行",
                    onClick = { choosingExpert = false; onRemote() },
                    kind = XfButtonKind.Secondary,
                    icon = XfIcons.Laptop,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            XfSurface(
                onClick = { browsingTeams = vm.teamMode; choosingExpert = true },
                shape = RoundedCornerShape(XfDimens.controlRadius),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    XfAvatar(
                        icon = if (vm.teamMode) XfIcons.Users else XfIcons.Brand,
                        size = 26.dp,
                    )
                    Text(
                        selectedName,
                        style = XfType.bodyStrong,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(XfIcons.ChevDown, "切换专家或团队", Modifier.size(16.dp), tint = colors.ink4)
                }
            }
            Spacer(Modifier.width(6.dp))
            XfIconButton(XfIcons.Chat, "最近对话", { choosingRecent = true })
            XfIconButton(XfIcons.Users, "本机群聊", onRooms)
            XfIconButton(XfIcons.Cpu, "模型配置", onModels)
            XfIconButton(XfIcons.Tasks, "任务记录", onTasks)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            XfChip(
                text = "此手机",
                icon = Icons.Outlined.Smartphone,
                onClick = { choosingLocation = true },
            )
            Spacer(Modifier.weight(1f))
            XfStatusChip(
                label = if(readiness.ready) "模型已配置" else readiness.label,
                tone = if(readiness.ready) XfTone.Success else XfTone.Warning,
            )
        }
        if (!readiness.ready && readiness.reason.isNotBlank()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                XfBanner(
                    text = readiness.reason,
                    tone = XfTone.Warning,
                    icon = XfIcons.Bell,
                    action = if(readiness.remote) "连接电脑" else "配置",
                    onAction = { if(readiness.remote) onRemote() else onModels() },
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSetup) item(key = "model-setup") {
                XfCard {
                    XfSectionHeader(title = "选择开始方式", subtitle = "先配置模型或连接电脑，再开始对话。")
                    Spacer(Modifier.height(10.dp))
                    XfButton(
                        text = "使用试用 · 未接入",
                        onClick = {},
                        enabled = false,
                        kind = XfButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("XFriend 暂未接入试用服务。", style = XfType.meta, color = colors.ink3)
                    Spacer(Modifier.height(10.dp))
                    XfButton(
                        text = "配置自己的模型",
                        onClick = onModels,
                        icon = XfIcons.Cpu,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    XfButton(
                        text = "连接电脑",
                        onClick = onRemote,
                        kind = XfButtonKind.Secondary,
                        icon = XfIcons.Laptop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("连接后检查电脑执行条件；本次对话不会自动切换。", style = XfType.meta, color = colors.ink3)
                }
            }
            if (showWelcome) {
                item(key = "greeting") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("今天想完成什么？", style = XfType.h1, color = colors.ink)
                            Text("和助手一起，把想法变成下一步。", style = XfType.small, color = colors.ink2)
                        }
                        Spacer(Modifier.width(12.dp))
                        XfStarMark(size = 52.dp)
                    }
                }
                item(key = "hero") {
                    ChatComposer(
                        draft = vm.draft,
                        onDraftChange = { vm.draft = it },
                        placeholder = "描述你的目标…",
                        sendEnabled = vm.draft.isNotBlank() && vm.selected.isNotBlank() && readiness.ready,
                        onSend = { vm.send() },
                        hint = "将自动匹配最合适的专家与技能",
                        onHintAction = { browsingTeams = vm.teamMode; choosingExpert = true },
                        modeLabels = vm.supportedConversationModes.map { conversationModeLabel(it) },
                        modeIndex = vm.supportedConversationModes.indexOf(vm.conversationMode).coerceAtLeast(0),
                        onModeSelect = { index ->
                            if (!vm.busy) {
                                vm.supportedConversationModes.getOrNull(index)?.let { vm.saveConversationMode(it) }
                            }
                        },
                    )
                }
                item(key = "suggestions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XfSectionLabel("常用任务")
                        val suggestions = listOf(
                            "整理资料" to "请帮我整理资料，先询问需要处理的材料和目标。",
                            "写工作计划" to "请帮我制定工作计划，先明确目标、时间和限制。",
                            "总结文档" to "请帮我总结文档，先确认文档内容和我关心的重点。",
                            "分析问题" to "请帮我分析问题，先向我了解背景、事实和目标。",
                        )
                        suggestions.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (title, prompt) ->
                                    XfSurface(
                                        onClick = { vm.draft = prompt },
                                        shape = RoundedCornerShape(XfDimens.controlRadius),
                                        color = colors.surface2,
                                        border = false,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Icon(XfIcons.Sparkles, null, Modifier.size(16.dp), tint = colors.accent)
                                            Text(title, style = XfType.small, color = colors.ink, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "status") {
                    XfCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.Fingerprint, size = XfDimens.avatarMedium)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("执行端 · 此手机", style = XfType.bodyStrong, color = colors.ink)
                                Text(
                                    if (readiness.ready) "模型已配置 · 可开始对话" else readiness.reason.ifBlank { readiness.label },
                                    style = XfType.meta,
                                    color = colors.ink3,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            XfStatusChip(
                                label = if (readiness.ready) "就绪" else "待配置",
                                tone = if (readiness.ready) XfTone.Success else XfTone.Warning,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            XfButton(
                                text = "更改执行位置",
                                onClick = { choosingLocation = true },
                                kind = XfButtonKind.Secondary,
                                small = true,
                                icon = XfIcons.Pin,
                            )
                            XfButton(
                                text = "模型配置",
                                onClick = onModels,
                                kind = XfButtonKind.Ghost,
                                small = true,
                                icon = XfIcons.Cpu,
                            )
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent-header") {
                        XfSectionHeader(
                            title = "继续最近的工作",
                            subtitle = "按最近使用排序",
                            action = "最近对话",
                            onAction = { choosingRecent = true },
                        )
                    }
                    items(recent, key = { "recent:${it.key}" }) { entry ->
                        val isTeam = entry.key.startsWith("team:")
                        val id = entry.key.removePrefix("team:")
                        val name = if(isTeam) s.teams.firstOrNull { it.id == id }?.name else s.experts.firstOrNull { it.id == id }?.name
                        if (name != null) {
                            XfCard(padding = 4.dp) {
                                XfListRow(
                                    title = name,
                                    subtitle = entry.value.last().content,
                                    meta = if (isTeam) "团队" else "专家",
                                    leading = {
                                        XfAvatar(icon = if (isTeam) XfIcons.Users else XfIcons.Brand, size = XfDimens.avatarSmall)
                                    },
                                    trailing = {
                                        Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                                    },
                                    onClick = { vm.teamMode = isTeam; vm.selected = id },
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            items(history) { message ->
                val isUser = message.role == "user"
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Text(
                        if (isUser) "你" else selectedName,
                        style = XfType.meta,
                        color = colors.ink3,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                    )
                    if (isUser) {
                        XfSurface(
                            shape = RoundedCornerShape(XfDimens.cardRadius),
                            color = colors.accentSolid,
                            border = false,
                            modifier = Modifier.widthIn(max = 320.dp),
                        ) {
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                SelectionContainer {
                                    Text(message.content, style = XfType.body, color = Color.White)
                                }
                            }
                        }
                    } else {
                        XfSurface(
                            shape = RoundedCornerShape(XfDimens.cardRadius),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                SalesMarkdown(message.content)
                            }
                        }
                    }
                }
            }
            if (run != null && run.state != "completed") item {
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        XfStatusChip(
                            label = friendRunLabel(run.state),
                            tone = if (run.active) XfTone.Accent else XfTone.Warning,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("$selectedName · 此手机", style = XfType.meta, color = colors.ink3)
                    }
                    if(run.active) {
                        Spacer(Modifier.height(8.dp))
                        XfButton(
                            text = "查看任务",
                            onClick = onTasks,
                            kind = XfButtonKind.Ghost,
                            small = true,
                        )
                    }
                    if (run.partial.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SalesMarkdown(run.partial)
                    }
                    if (run.errorCode.isNotBlank() && run.errorMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        XfBanner(text = run.errorMessage, tone = XfTone.Danger, icon = XfIcons.Bell)
                    }
                    if (run.canVerifyCheckpoint) {
                        Spacer(Modifier.height(8.dp))
                        XfButton(
                            text = "核查写入并继续",
                            onClick = { vm.verifyRun(run) },
                            kind = XfButtonKind.Secondary,
                            small = true,
                        )
                    }
                    if (run.canResume) {
                        Spacer(Modifier.height(8.dp))
                        XfButton(
                            text = "从已保存进度继续",
                            onClick = { vm.resumeRun(run) },
                            kind = XfButtonKind.Secondary,
                            small = true,
                        )
                    }
                    if (run.state in listOf("failed", "interrupted", "cancelled", "requires_review")) {
                        Spacer(Modifier.height(8.dp))
                        XfButton(
                            text = "取回输入，重新发起",
                            onClick = { vm.retryRun(run) },
                            kind = XfButtonKind.Secondary,
                            small = true,
                        )
                    }
                }
            }
        }
        if (vm.executing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                Text(
                    if(run?.active == true) "此手机正在执行，可继续编辑草稿" else "其他对话正在执行，请到任务中心查看",
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = XfType.meta,
                    color = colors.ink3,
                )
                XfButton(text = "停止", onClick = vm::cancel, kind = XfButtonKind.Ghost, small = true)
            }
        } else if (vm.status.isNotBlank()) {
            Text(
                vm.status,
                Modifier.padding(horizontal = 16.dp),
                style = XfType.meta,
                color = colors.ink3,
            )
        }
        if (!showWelcome) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChatComposer(
                    draft = vm.draft,
                    onDraftChange = { vm.draft = it },
                    placeholder = if(vm.executing) "可以先写下一条消息…" else "告诉我想完成什么…",
                    sendEnabled = vm.draft.isNotBlank() && vm.selected.isNotBlank() && readiness.ready,
                    onSend = { vm.send() },
                    hint = null,
                    onHintAction = null,
                    modeLabels = vm.supportedConversationModes.map { conversationModeLabel(it) },
                    modeIndex = vm.supportedConversationModes.indexOf(vm.conversationMode).coerceAtLeast(0),
                    onModeSelect = { index -> if (!vm.busy) vm.supportedConversationModes.getOrNull(index)?.let { vm.saveConversationMode(it) } },
                    enabled = !vm.busy,
                )
            }
        }
    }
}

internal fun conversationModeLabel(mode: String): String = when (mode) {
    ConversationMode.ASK -> "问答"
    ConversationMode.PLAN -> "计划"
    ConversationMode.CRAFT -> "执行"
    else -> mode
}

@Composable internal fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    hint: String?,
    onHintAction: (() -> Unit)?,
    modeLabels: List<String>,
    modeIndex: Int,
    onModeSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    val colors = xf
    XfSurface(shape = RoundedCornerShape(XfDimens.heroRadius), border = true) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (hint != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(XfIcons.Sparkles, null, Modifier.size(15.dp), tint = colors.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(hint, style = XfType.meta, color = colors.ink3, modifier = Modifier.weight(1f))
                    if (onHintAction != null) XfLink("更改", onClick = onHintAction)
                }
                Spacer(Modifier.height(6.dp))
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                textStyle = XfType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 5,
                minLines = if (hint != null) 3 else 2,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (draft.isEmpty()) Text(placeholder, style = XfType.body, color = colors.ink4)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (modeLabels.isNotEmpty()) {
                    XfSegmented(
                        options = modeLabels,
                        selectedIndex = modeIndex.coerceIn(0, modeLabels.lastIndex),
                        onSelect = onModeSelect,
                        modifier = Modifier.weight(1f),
                    )
                } else Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                ChatSendButton(enabled = sendEnabled, onClick = onSend)
            }
        }
    }
}

@Composable private fun ChatSendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = xf
    Box(
        Modifier.size(XfDimens.buttonHeight).clip(CircleShape)
            .background(if (enabled) colors.accentSolid else colors.surface3)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            XfIcons.ArrowUp,
            contentDescription = "发送",
            modifier = Modifier.size(20.dp),
            tint = if (enabled) Color.White else colors.ink4,
        )
    }
}

internal fun friendRunLabel(state: String): String = when (state) {
    "queued" -> "等待执行"; "running" -> "正在执行"; "completed" -> "已完成"; "interrupted" -> "已中断 · 记录已保留"; "cancelled" -> "已停止"; "requires_review" -> "操作结果待核查"; else -> "未完成"
}

@Composable internal fun FriendTaskScreen(vm: FriendViewModel, back: () -> Unit) {
    val colors = xf
    BackHandler(onBack = back)
    LaunchedEffect(Unit) { vm.refreshPhoneTasks() }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = vm.runList.filter { when(filter) {
        "进行中" -> it.active
        "待处理" -> it.state in setOf("interrupted", "failed", "requires_review")
        "已完成" -> it.state == "completed"
        else -> true
    } }.sortedByDescending { it.created }
    val phoneTasks = UnifiedTaskProjection.filter(vm.unifiedTasks, if (filter == "已完成") "全部" else filter)
        .filter { it.source == UnifiedTaskProjection.SOURCE_PHONE }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = "任务中心",
                subtitle = "离开页面不会停止任务。有安全检查点的任务可以继续；重新发起会创建新任务，并可能再次执行工具。",
            )
        }
        if (Build.VERSION.SDK_INT >= 33) item {
            XfButton(
                text = "允许任务通知",
                onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                kind = XfButtonKind.Secondary,
                icon = XfIcons.Bell,
                small = true,
            )
        }
        item {
            val filters = listOf("全部", "进行中", "待处理", "已完成")
            XfSegmented(
                options = filters,
                selectedIndex = filters.indexOf(filter).coerceAtLeast(0),
                onSelect = { filter = filters[it] },
            )
        }
        if (vm.runList.isEmpty()) item { EmptyHint("还没有运行记录", "发送任务后，结果、未完成输出和失败原因会保存在这里。") }
        if(filtered.isEmpty() && vm.runList.isNotEmpty()) item { EmptyHint("暂无${filter}任务", "切换筛选查看其他运行记录。") }
        items(filtered, key = { it.id }) { run ->
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        run.localRoomTurn?.let { turn -> vm.store?.localRooms?.find { it.id == turn.roomId }?.name ?: "本机群聊" }
                            ?: vm.store?.experts?.find { it.id == run.resourceId }?.name ?: vm.store?.teams?.find { it.id == run.resourceId }?.name ?: "智能体任务",
                        style = XfType.title,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    XfStatusChip(
                        label = friendRunLabel(run.state),
                        tone = if (run.state == "completed") XfTone.Success else if (run.active) XfTone.Accent else XfTone.Warning,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfStatusChip(label = "此手机", tone = XfTone.Neutral)
                    Spacer(Modifier.width(6.dp))
                    XfStatusChip(label = if (run.active) "运行中" else "已结束", tone = if (run.active) XfTone.Accent else XfTone.Neutral)
                }
                Spacer(Modifier.height(8.dp))
                Text(run.input, style = XfType.small, color = colors.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (run.errorCode.isNotBlank() && run.errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    XfBanner(text = run.errorMessage, tone = XfTone.Danger, icon = XfIcons.Bell)
                }
                if (run.diagnosticId.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("诊断编号：${run.diagnosticId}", style = XfType.meta, color = colors.ink3)
                }
                if (run.partial.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    XfButton(
                        text = if(expanded == run.id) "收起结果" else "查看结果",
                        onClick = { expanded = if(expanded == run.id) null else run.id },
                        kind = XfButtonKind.Ghost,
                        small = true,
                    )
                    if(expanded == run.id) {
                        Spacer(Modifier.height(8.dp))
                        XfSurface(shape = RoundedCornerShape(XfDimens.smallRadius), color = colors.surface2, border = false) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) { SalesMarkdown(run.partial) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (run.canVerifyCheckpoint) {
                        XfButton(
                            text = "核查写入并继续",
                            onClick = { vm.verifyRun(run); back() },
                            kind = XfButtonKind.Secondary,
                            small = true,
                        )
                    }
                    if (run.canResume) {
                        XfButton(
                            text = "从已保存进度继续",
                            onClick = { vm.resumeRun(run); back() },
                            kind = XfButtonKind.Secondary,
                            small = true,
                        )
                    }
                    if (!run.active) {
                        XfButton(
                            text = "取回输入",
                            onClick = { vm.retryRun(run); back() },
                            kind = XfButtonKind.Secondary,
                            small = true,
                        )
                    } else {
                        XfButton(
                            text = "停止任务",
                            onClick = vm::cancel,
                            kind = XfButtonKind.Danger,
                            small = true,
                        )
                    }
                }
            }
        }
        item { XfSectionHeader("本机事项与提醒", subtitle = "与执行任务分开保存；隐藏管理器不会取消它们。") }
        if (phoneTasks.isEmpty()) item { EmptyHint("没有本机事项", "在默认执行模式下可直接记录日期型事项或提醒。") }
        items(phoneTasks, key = { "phone:${it.id}" }) { task ->
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, style = XfType.title, color = colors.ink, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    XfStatusChip(label = when (task.state) {
                        "completed" -> "已保存"; "open" -> "待处理"; "scheduled" -> "已安排提醒"
                        "requires_review" -> "待核查"; "missed" -> "已错过"; "cancelled" -> "已取消"; else -> task.state
                    }, tone = if (task.state == "open" || task.state == "scheduled") XfTone.Accent else XfTone.Neutral)
                }
                if (task.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(task.detail, style = XfType.meta, color = colors.ink3)
                }
            }
        }
    }
}

@Composable internal fun SettingsPage(s: Store, vm: FriendViewModel, platform: () -> Unit, onProviders: (() -> Unit)? = null, profiles: @Composable () -> Unit = {}) {
    val colors = xf
    var url by rememberSaveable(s.baseUrl) { mutableStateOf(s.baseUrl) }
    var model by rememberSaveable(s.model) { mutableStateOf(s.model) }
    var tools by rememberSaveable(s.modelUseTools) { mutableStateOf(s.modelUseTools) }
    val key = vm.credentialDraft ?: s.apiKey
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { runCatching { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(vm.lastDiagnostic) }
        } }.onFailure { vm.error = "诊断导出失败，请重新选择文件位置" } }
    }
    val blocked = vm.busy || vm.testingModel
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = "连接你的模型",
                subtitle = "配置和测试 OpenAI 兼容服务，明确检查模型是否支持工具。",
            )
        }
        if (onProviders != null) item {
            XfButton("管理多个供应商与账号", onProviders, kind = XfButtonKind.Secondary)
        }
        item { profiles() }
        item {
            XfCard {
                XfTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "基础服务地址",
                    placeholder = "https://服务域名/v1",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                XfTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "模型名称",
                    placeholder = "填写服务商模型名",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                XfTextField(
                    value = key,
                    onValueChange = { vm.credentialDraft = it },
                    label = "API Key",
                    placeholder = "密钥只保存在本机加密存储",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("启用工具调用", style = XfType.bodyStrong, color = colors.ink)
                        Text("模型不支持工具时关闭，仍可进行普通对话。", style = XfType.meta, color = colors.ink3)
                    }
                    Switch(checked = tools, onCheckedChange = { tools = it }, enabled = !blocked)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                XfButton(
                    text = "保存配置",
                    onClick = { vm.saveModel(url, model, key, tools) },
                    enabled = !blocked && model.isNotBlank(),
                    icon = XfIcons.Check,
                )
                XfButton(
                    text = if (vm.testingModel) "测试中…" else "测试连接",
                    onClick = { vm.testModel(url, model, key, tools) },
                    enabled = !blocked && model.isNotBlank() && key.isNotBlank(),
                    kind = XfButtonKind.Secondary,
                    icon = XfIcons.Plug,
                    loading = vm.testingModel,
                )
            }
        }
        val notice = vm.modelNotice.ifBlank { vm.error.orEmpty() }
        if (notice.isNotBlank()) item {
            XfBanner(
                text = notice,
                tone = when {
                    vm.error != null -> XfTone.Danger
                    notice.startsWith("已保存") || notice.startsWith("模型连接成功") || notice.startsWith("模型认证连接成功") -> XfTone.Success
                    else -> XfTone.Neutral
                },
                icon = XfIcons.Bell,
            )
        }
        if (vm.modelTestResult.isNotBlank()) item {
            XfBanner(
                text = vm.modelTestResult,
                tone = if (vm.modelTestResult.contains("失败") || vm.modelTestResult.contains("错误")) XfTone.Danger else XfTone.Success,
                icon = XfIcons.Bell,
            )
        }
        item {
            XfBanner(
                text = "测试会向所填模型服务发送一条短请求。完整 chat/completions 地址会在保存时规范化。密钥只保存在本机加密存储。",
                tone = XfTone.Neutral,
                icon = XfIcons.Shield,
            )
        }
        if (vm.lastDiagnostic.isNotBlank()) item {
            XfButton(
                text = "导出脱敏诊断",
                onClick = { diagnosticExport.launch("Xfriend-连接诊断.json") },
                kind = XfButtonKind.Secondary,
                icon = XfIcons.Export,
                small = true,
            )
        }
        item {
            XfCard(padding = 4.dp) {
                XfListRow(
                    title = "平台连接",
                    subtitle = "协同平台账号及本人设备",
                    leading = { XfIconTile(XfIcons.Laptop, size = 34.dp) },
                    trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                    onClick = platform,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("星助理单入口", style = XfType.h3, color = colors.ink)
                        Text("重新启用单一入口；关闭时保留原四个页签与全部数据。", style = XfType.meta, color = colors.ink3)
                    }
                    Switch(checked = s.starUi, onCheckedChange = { on -> vm.change { it.starUi = on } })
                }
            }
        }
        item {
            EmptyHint("执行位置", "手机执行基础工具与知识检索；完整电脑专家请使用电脑执行入口。已提交手机任务可离开页面，系统强制停止后需重新打开并手动发起。")
        }
    }
}
