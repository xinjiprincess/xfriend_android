package com.singu.xfriend

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

private fun Expert.isAssistantAgent(): Boolean = id == "xfriend" || id.startsWith("agent-")

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AssistantRoot(friend: FriendViewModel, account: AccountViewModel?, onDesktop: () -> Unit) {
    val context = LocalContext.current
    val app = LocalContext.current.applicationContext as Application
    // Reuse the bundle protocol and encrypted model profiles without entering SalesRoot.
    val resources: SalesViewModel = viewModel(key = if (ProductBrand.isSales) "sales-${friend.accountScope}" else "assistant-resources-${friend.accountScope}", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SalesViewModel(app, friend.accountScope) { account?.account?.session?.optJSONObject("lease") } as T
    })
    DisposableEffect(resources) { onDispose { resources.cancel(); resources.stopPlayback() } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
        var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var editorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by rememberSaveable { mutableStateOf("") }
    var savingEditor by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(friend.busy, savingEditor) { if(savingEditor && !friend.busy) { if(friend.error == null) editorKind = null; savingEditor = false } }
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var desktopImport by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    LaunchedEffect(friend.requestedLocalRoomId) {
        if (friend.requestedLocalRoomId != null) detail = "local_rooms"
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) foreground = false
            if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun backDetail() { detail = when(detail) { "remote", "platform" -> "connections"; "legacy_models" -> "models"; else -> null } }
    BackHandler(enabled = detail != null) { backDetail() }
    var inspectedTaskId by remember { mutableStateOf<String?>(null) }
    val s = friend.store
    val revision = friend.revision
    // Single-entry star assistant by default; the classic workspace stays behind a switch.
    val star = s?.starUi == true
    val blocked = friend.busy || resources.busy
    fun edit(kind: String, id: String = "") { editorKind = kind; editorId = id }
    fun connectDesktop() {
        if (!resources.busy && (!friend.busy || friend.executing)) {
            onDesktop()
        }
    }
    val skillFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> friend.import(uri, true) } }
    val knowledgeFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> friend.import(uri, false) } }

    var importUri by remember { mutableStateOf<Uri?>(null) }
    val packageFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importUri = it }
    val titles = listOf("对话", "工作台", "资源", "我的")
    val icons = listOf(XfIcons.Chat, XfIcons.Grid, XfIcons.Folder, XfIcons.User)
    if (account != null) SalesCloudSyncBinding(friend, resources, account)
    fun route(value: String) { detail = value }
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    Scaffold(
        topBar = {
            if ((detail != null || !star) && detail != "phone_tools") XfTopBar(
                title = when (detail) {
                    "models" -> "供应商与账号"; "legacy_models" -> "旧模型配置与导入"; "phone_tools" -> "手机能力"; "platform" -> "平台设备"; "migration" -> "迁移销售数据"
                    "tasks" -> "任务中心"; "remote" -> "电脑会话"; "connections" -> "电脑与平台"
                    "resources" -> "资源"; "blueprint" -> "智能体蓝图"; "market" -> "发现"
                    "cloud" -> "云协作"; "local_rooms" -> "本机群聊"; "sync" -> "账号与同步"; "designer" -> "设计专家"; "appearance" -> "外观"
                    "settings" -> "设置"; "memory" -> "长期记忆"
                    else -> if (tab == 0) ProductBrand.name else titles[tab.coerceIn(0, 3)]
                },
                onBack = if (detail != null) ::backDetail else null,
                brandIconRes = if (detail == null) ProductBrand.icon else null,
                actions = { if (detail == null && !star) XfIconButton(XfIcons.Tasks, "任务中心", onClick = { route("tasks") }) },
            )
        },
        bottomBar = { if(detail == null && !keyboard && !star) ProductBottomBar(
            items=titles.mapIndexed { i,title -> ProductNavigationItem(i,title,icons[i]) },selectedId=tab,hazeState=haze,onSelect={tab=it}) }

    ) { padding ->
        Column(Modifier.fillMaxSize().productHazeSource(haze).padding(padding)) {
            if(resources.busy || (friend.executing && (tab != 0 || detail != null))) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                Text(if(resources.busy) "正在处理资源…" else "任务继续运行中",Modifier.weight(1f).padding(8.dp),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={if(resources.busy) resources.cancel() else route("tasks")}) { Text(if(resources.busy) "取消" else "查看") }
            }
            (if (star) resources.error else friend.error ?: resources.error)?.let { error ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    XfBanner(
                        text = error,
                        tone = XfTone.Danger,
                        icon = XfIcons.Bell,
                        action = "关闭",
                        onAction = { friend.error = null; resources.error = null },
                    )
                }
            }
            if(s == null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text(if(friend.error == null) "正在读取工作空间…" else "工作空间暂不可用") }
            else {
                @Suppress("UNUSED_VARIABLE") val observedRevision=revision
                Crossfade(targetState=detail ?: "tab:$tab",animationSpec=tween(180),label="page") { destination ->
                    when(destination) {
                    "models" -> FriendProvidersPage(s, friend, back = { detail = null }, onLegacy = { route("legacy_models") })
                    "legacy_models" -> SettingsPage(s,friend,{route("connections")},onProviders={route("models")}) { AssistantModelProfiles(resources,friend) }
                    "phone_tools" -> PhoneCapabilitiesPage(friend.accountScope) { detail = null }
                    "memory" -> MemoryScreen(s, friend)
                    "platform" -> PlatformScreen(friend.accountScope)
                    "migration" -> SalesMigrationScreen(friend) { detail=null }
                    "tasks" -> if (star) SubagentTasksPage(s, friend, back = { detail=null }, initialRunId = inspectedTaskId) else FriendTaskScreen(friend) { detail=null }
                    "remote" -> FriendRemoteScreen(friend.accountScope) { route("connections") }
                    "local_rooms" -> LocalRoomsScreen(friend, onModels = { route("models") }, onTasks = { route("tasks") })
                    "blueprint" -> FriendBlueprintScreen(friend) { detail=null }
                    "market" -> SalesMarketScreen(friend,{kind,query,index -> (account?.account ?: error("请登录云账号")).authorizedGet("/api/v2/catalog/$kind",mapOf("q" to query,"page" to index.toString()),friend.accountScope)}) { detail=null }
                    "designer" -> SalesExpertScreen(friend,resources,{detail=null;tab=1}) { detail=null }
                    "sync" -> if(account != null) SalesCloudSyncScreen(friend,resources,account) {detail=null} else EmptyHint("请先登录","同步需要已登录的账号与空间。")
                    "appearance" -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        XfPageHeader("外观", subtitle = "暖纸色画布、单一主色与统一图标语言；设置只保存在本机。")
                        XfAppearancePanel()
                        XfCard {
                            Text("通知", style = XfType.h3, color = xf.ink)
                            Spacer(Modifier.height(2.dp))
                            Text("任务与诊断通过系统通知提醒，可在系统设置中调整权限与声音。", style = XfType.meta, color = xf.ink3)
                            Spacer(Modifier.height(10.dp))
                            XfButton(
                                "打开系统通知设置",
                                {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                },
                                kind = XfButtonKind.Secondary,
                                small = true,
                            )
                        }
                    }
                    "connections" -> LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item { Text("选择工作位置",style=MaterialTheme.typography.titleLarge);Text("手机可独立运行专家与群聊；电脑和云协作使用各自授权的资源。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { AssistantMenu("本机专家群聊","由此手机组织多位专家，一轮协作、保存群消息") {route("local_rooms")} }
                        item { AssistantMenu("电脑会话","原生对话、任务进度与操作确认") {route("remote")} }
                        item { AssistantMenu("云协作","已有云群、邀请与跨设备消息") {route("cloud")} }
                        item { AssistantMenu("打开电脑工作台","复杂配置、远程控制与电脑专属功能",!blocked,::connectDesktop) }
                        item { AssistantMenu("平台设备","协同平台账号及本人设备") {route("platform")} }
                        item { AssistantMenu("从电脑导入资源","先预览配置和模型，再确认保存",!blocked && resources.data != null) {desktopImport=true} }
                    }
                    "resources", "tab:2" -> FriendResourcePage(
                        store=s,revision=revision,blocked=blocked,
                        onEdit={kind,id -> edit(kind,id)},onDelete={kind,id -> deleting=kind to id},
                        onDesktopImport={desktopImport=true},
                        onSkillImport={skillFile.launch(arrayOf("*/*"))},
                        onKnowledgeImport={knowledgeFile.launch(arrayOf("*/*"))},
                        onMarket={route("market")},
                    )
                    "cloud" -> if(account?.account != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { CloudRoomsPanel(account.account!!) } else EmptyHint("请先登录", "云协作需要账号与空间。")
                    "tab:0" -> if (star) StarChatPage(
                        s, friend,
                        onSettings = { route("settings") },
                        onModels = { route("models") },
                        onTasks = { inspectedTaskId = null; route("tasks") },
                        onTaskDetails = { inspectedTaskId = it; route("tasks") },
                        onRooms = { route("local_rooms") },
                        onRemote = { route("connections") },
                    ) else ChatPage(s,friend,onModels={route("models")},onRemote={route("connections")},onTasks={route("tasks")},onRooms={route("local_rooms")},onCloud={route("cloud")})
                    "settings" -> StarSettingsPage(s, friend,
                        onPhoneTools = { route("phone_tools") },
                        onMemory = { route("memory") },
                        onModels = { route("models") },
                        onTasks = { route("tasks") },
                        onRooms = { route("local_rooms") },
                        onRemote = { route("connections") },
                    )
                    "tab:1" -> FriendLibraryPage(s,blocked,onChoose={id,team -> friend.selected=id;friend.teamMode=team;tab=0},onEdit={kind,id->edit(kind,id)},onDelete={kind,id->deleting=kind to id},onRoute=::route,onImport={packageFile.launch(arrayOf("*/*"))})
                    else -> FriendAccountPage(s, account, !blocked, route=::route,
                        onImport={desktopImport=true}, onLogout={confirmLogout=true})

                    }
                }
            }
        }
    }
    FriendPackagePreview(resources,friend,importUri,{importUri=null})
    if (s != null) editorKind?.let { kind ->
        EditorDialog(if (kind == "agent") "expert" else kind, editorId, s, blocked, { editorKind = null }, agent = kind == "agent", error = friend.error) { name, content, skills, knowledge, members ->
            val id = editorId.ifBlank { (if (kind == "agent") "agent-" else "") + UUID.randomUUID().toString() }
            savingEditor = true
            friend.change { store -> when (kind) {
                "agent", "expert" -> store.experts = store.experts.filterNot { it.id == id } + Expert(id, name, content, skills, knowledge)
                "team" -> store.teams = store.teams.filterNot { it.id == id } + Team(id, name, members)
                "skill" -> store.skills = store.skills.filterNot { it.id == id } + Skill(id, name, content)
                else -> store.knowledge = store.knowledge.filterNot { it.id == id } + Knowledge(id, name, content)
            } }
        }
    }
    deleting?.let { (kind, id) ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("确认删除？") }, text = { Text("此操作不可撤销，相关关联也将移除。") }, confirmButton = {
            TextButton(enabled = !blocked, onClick = {
                friend.change { store -> when (kind) {
                    "expert" -> { store.experts = store.experts.filterNot { it.id == id }; store.teams = store.teams.map { it.copy(members = it.members - id) }; store.messages = store.messages - id }
                    "team" -> { store.teams = store.teams.filterNot { it.id == id }; store.messages = store.messages - "team:$id" }
                    "skill" -> { store.skills = store.skills.filterNot { it.id == id }; store.experts = store.experts.map { it.copy(skills = it.skills - id) } }
                    else -> { store.knowledge = store.knowledge.filterNot { it.id == id }; store.experts = store.experts.map { it.copy(knowledge = it.knowledge - id) } }
                } }
                deleting = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
    if (desktopImport) AssistantDesktopImport(resources, { desktopImport = false }) {
        desktopImport = false
        packageFile.launch(arrayOf("*/*"))
    }
    resources.bundle?.let { bundle ->
        var secrets by remember(bundle) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!blocked) resources.bundle = null }, title = { Text("确认导入电脑版资源") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${bundle.experts.size} 个智能体 / 专家 · ${bundle.teams.size} 个团队\n${bundle.skills.size} 项技能 · ${bundle.knowledge.size} 份知识\n${bundle.profiles.size} 项模型配置")
                Text("重复导入会更新同一资源，手机已有的其他资源会保留。导入后可在「我的 → 模型配置」选择模型。", style = MaterialTheme.typography.bodySmall)
                if (bundle.secretCount > 0) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(secrets, { secrets = it }, enabled = !blocked); Text("同时保存包内 ${bundle.secretCount} 项密钥", style = MaterialTheme.typography.bodySmall) }
                bundle.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { resources.acceptBundle(friend, secrets) }, enabled = !blocked) { Text("导入") } }, dismissButton = { TextButton(onClick = { resources.bundle = null }, enabled = !blocked) { Text("取消") } })
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出 ${ProductBrand.name}？") }, text = { Text(if (friend.executing) "退出将停止当前任务，本机已保存的数据仍保留。" else "本机已保存的数据仍保留，重新登录后可继续使用。") }, confirmButton = { TextButton(onClick = { confirmLogout = false; friend.cancel(); account?.logout() }) { Text("退出登录") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable internal fun AssistantMenu(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    XfCard(onClick = if (enabled) onClick else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = XfType.title, color = xf.ink)
                Text(subtitle, style = XfType.meta, color = xf.ink3)
            }
            Icon(XfIcons.ChevRight, null, Modifier.padding(start = 12.dp).size(16.dp), tint = xf.ink4)
        }
    }
}

@Composable private fun AssistantDesktopImport(vm: SalesViewModel, close: () -> Unit, file: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(context.getSharedPreferences("desktop_connection", 0).getString("address", "https://").orEmpty()) }
    var code by remember { mutableStateOf("") }
    var secrets by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("从电脑版导入") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("在电脑版启用手机连接，使用同一账号与工作空间配对；也可选择电脑导出的配置包或旧版 JSON 文件。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(address, { address = it }, label = { Text("电脑连接地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(code, { code = it }, label = { Text("配对码") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(secrets, { secrets = it }); Text("请求导出本人配置的模型密钥", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = file, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("选择配置包文件") }
        }
    }, confirmButton = { TextButton(onClick = { vm.fetchDesktop(address.trim(), code.trim(), secrets); code = ""; close() }, enabled = address.isNotBlank() && code.isNotBlank() && !vm.busy && vm.data != null) { Text("连接并预览") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable private fun AssistantModelProfiles(vm: SalesViewModel, friend: FriendViewModel) {
    val profiles = vm.data?.profiles.orEmpty()
    if (profiles.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("电脑版模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        profiles.forEach { profile ->
            val active = friend.store?.let { it.activeModelConnectionId.isBlank() && it.baseUrl == profile.url && it.model == profile.model && it.apiKey == profile.key && profile.key.isNotBlank() } == true
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                    Text(profile.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (profile.key.isBlank()) "未导入密钥；选择后可在下方补充" else "密钥已加密保存在本机", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { friend.credentialDraft = null; friend.change { it.activeModelConnectionId = ""; it.baseUrl = profile.url; it.model = profile.model; it.apiKey = profile.key } }, enabled = !friend.busy && !vm.busy) { Text(if (active) "当前模型" else "使用此配置") }
                }
            }
        }
        HorizontalDivider()
    }
}
