package com.singu.xfriend

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.*

@Composable internal fun SalesRoot(friend: FriendViewModel, account: AccountViewModel, onDesktop: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val sales: SalesViewModel = viewModel(key = "sales-${friend.accountScope}", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SalesViewModel(app, friend.accountScope) { account.account?.session?.optJSONObject("lease") } as T
    })
    SalesCloudSyncBinding(friend, sales, account)
    var legacy by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(friend.store) { friend.store?.let { st ->
        if (salesSkills.any { skill -> st.skills.none { it.id == skill.id } } || st.experts.any { it.id in salesSkillBindings && it.skills.isEmpty() }) friend.change { s ->
            s.skills = s.skills + salesSkills.filter { skill -> s.skills.none { it.id == skill.id } }
            s.experts = s.experts.map { e -> if (e.id in salesSkillBindings && e.skills.isEmpty()) e.copy(skills = listOf(salesSkills[salesSkillBindings.getValue(e.id)].id)) else e }
        }
        if (salesExperts.any { e -> st.experts.none { it.id == e.id } } || st.teams.none { it.id == salesTeam.id }) friend.change { s -> s.experts = s.experts + salesExperts.filter { e -> s.experts.none { it.id == e.id } }; if (s.teams.none { it.id == salesTeam.id }) s.teams = s.teams + salesTeam }
    } }
    DisposableEffect(Unit) { onDispose { sales.cancel(); sales.stopPlayback() } }
    if (legacy) {
        BackHandler { legacy = false }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                XfIconButton(XfIcons.ChevRight, "返回 Xsalers", { legacy = false }, rotation = 180f)
                Text("返回 Xsalers", style = XfType.bodyStrong, color = xf.ink2)
            }
            Box(Modifier.weight(1f)) { XfriendApp(friend, onDesktop) }
        }
    } else SalesScreen(sales, friend, onDesktop, { legacy = true }, onLogout = { friend.cancel(); account.logout() }, cloudAccount = account.account, accountViewModel = account)
}

@Composable internal fun SalesScreen(vm: SalesViewModel, friend: FriendViewModel, onDesktop: () -> Unit, onLibrary: () -> Unit, onLogout: () -> Unit = {}, cloudAccount: CloudAccount? = null, accountViewModel: AccountViewModel? = null) {
    ProductTheme { SalesWorkspace(vm, friend, onDesktop, onLibrary, onLogout, cloudAccount, accountViewModel) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun SalesWorkspace(vm: SalesViewModel, friend: FriendViewModel, onDesktop: () -> Unit, onLibrary: () -> Unit, onLogout: () -> Unit, cloudAccount: CloudAccount?, accountViewModel: AccountViewModel?) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val colors = xf
    val d = vm.data
    var page by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var customerEditor by remember { mutableStateOf<Customer?>(null) }
    var customerDetail by rememberSaveable { mutableStateOf("") }
    var recordEditor by remember { mutableStateOf<Communication?>(null) }
    var followUpEditor by remember { mutableStateOf<FollowUp?>(null) }
    var taskCenter by rememberSaveable { mutableStateOf(false) }
    var callRecordings by rememberSaveable { mutableStateOf(false) }
    var crmOverview by rememberSaveable { mutableStateOf(false) }
    var crmTab by rememberSaveable { mutableIntStateOf(0) }
    var models by rememberSaveable { mutableStateOf(false) }
    var experts by rememberSaveable { mutableStateOf(false) }
    var expertStartSection by rememberSaveable { mutableStateOf("home") }
    var market by rememberSaveable { mutableStateOf(false) }
    var cloudSync by rememberSaveable { mutableStateOf(false) }
    var migration by rememberSaveable { mutableStateOf(false) }
    var subscription by remember { mutableStateOf(false) }
    var signOut by remember { mutableStateOf(false) }
    var desktopImport by remember { mutableStateOf(false) }
    var screenshotSelection by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var importFile by remember { mutableStateOf<Uri?>(null) }
    var exportPack by remember { mutableStateOf("") }
    var exportOptions by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var phoneGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
    var smsCustomer by remember { mutableStateOf<Customer?>(null) }
    // 内联「外观」子页：与概览页共用同一个 tab 容器，返回时回到「我的」。
    var appearance by rememberSaveable { mutableStateOf(false) }
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importMedia(uri, false); page = 0 } }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) { screenshotSelection = it } }
    val textFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importText(uri); page = 0 } }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val options = exportOptions; if (uri != null && options != null) vm.exportPortable(uri, friend, options.first, options.second, options.third); exportOptions = null }
    val bundleFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFile = it }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startRecording(friend.store) else vm.error = "未获得麦克风权限，可导入录音文件" }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.importContacts() else vm.error = "未获得通讯录权限，可手动创建客户" }
    val phone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { phoneGranted = it; if (!it) vm.error = "未获得电话状态权限，请在来电前手动停止录音" }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) smsCustomer?.let(vm::importSms) else vm.error = "系统未允许短信读取。请选择“导入文本”导入已导出的客户短信"; smsCustomer = null }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { vm.stopRecording(); vm.stopPlayback() } }
        owner.lifecycle.addObserver(listener)
        onDispose { owner.lifecycle.removeObserver(listener) }
    }
    @Suppress("DEPRECATION")
    DisposableEffect(phoneGranted) {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val listener = object : PhoneStateListener() { override fun onCallStateChanged(state: Int, number: String?) { if (state != TelephonyManager.CALL_STATE_IDLE) vm.stopRecording() } }
        if (phoneGranted) runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
        onDispose { if (phoneGranted) runCatching { tm.listen(listener, PhoneStateListener.LISTEN_NONE) } }
    }
    val titles = listOf("今日", "客户", "复盘", "专家", "我的")
    val subtitles = listOf("把每一次沟通，推进成下一步", "关系有脉络，跟进有节奏", "留住对话里的每个关键", "专业判断，来自真实证据", "为你的销售工作而设")
    val icons = listOf(XfIcons.Home, XfIcons.Users, XfIcons.Chart, XfIcons.Sparkles, XfIcons.User)
    LaunchedEffect(vm.notice) { if (vm.notice.isNotBlank()) { kotlinx.coroutines.delay(6000); vm.dismissNotice() } }
    val blocked = vm.busy || vm.recording || friend.busy
    val deep = models || experts || market || cloudSync || migration || taskCenter || callRecordings || crmOverview || customerDetail.isNotBlank() || recordEditor != null
    BackHandler(enabled = !deep && page != 0) { page = 0; search = ""; appearance = false }
    BackHandler(enabled = appearance) { appearance = false }
    Scaffold(containerColor = colors.canvas,
        bottomBar = {
            if (!deep && WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) == 0) ProductBottomBar(
                items = listOf(0, 1, 2, 3, 4).map { ProductNavigationItem(it, titles[it], icons[it]) },
                selectedId = page,
                hazeState = haze,
                onSelect = { page = it; search = ""; appearance = false },
                supportingContent = { if (vm.playback.recordId.isNotBlank()) SalesPlayer(vm, compact = true) },
            )
        },
        floatingActionButton = {}
    ) { inset -> Column(Modifier.fillMaxSize().productHazeSource(haze).padding(top = if (callRecordings) 0.dp else inset.calculateTopPadding())) {
        val activeTask = vm.tasks.firstOrNull { it.active }
        val failedTask = vm.tasks.any { it.status == "failed" }
        if (!callRecordings && activeTask != null) {
            Box(Modifier.padding(horizontal = XfDimens.pagePadding, vertical = 6.dp)) {
                SalesActiveTaskCard(activeTask, d?.records?.find { it.id == activeTask.recordId }) { taskCenter = true }
            }
        } else if (!callRecordings && failedTask) {
            Box(Modifier.padding(horizontal = XfDimens.pagePadding, vertical = 6.dp)) {
                XfBanner("有复盘需要重试", tone = XfTone.Warning, icon = XfIcons.Bell, action = "查看任务", onAction = { taskCenter = true })
            }
        }
        if (!callRecordings && (vm.busy || friend.busy)) XfProgressBar(progress = null)
        val error = vm.error ?: friend.error
        if (!callRecordings && (vm.notice.isNotBlank() || error != null)) {
            Box(Modifier.padding(horizontal = XfDimens.pagePadding, vertical = 6.dp)) {
                XfBanner(
                    text = error ?: vm.notice,
                    tone = if (error != null) XfTone.Danger else XfTone.Info,
                    icon = if (error != null) XfIcons.Bell else XfIcons.CheckCircle,
                    action = if (vm.busy) "取消" else "关闭",
                    onAction = { if (vm.busy) vm.cancel() else { vm.dismissNotice(); friend.error = null } },
                )
            }
        }
        when {
            d == null || friend.store == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            callRecordings -> { BackHandler { callRecordings = false }; SalesCallRecordingsScreen(vm, friend) { callRecordings = false } }
            recordEditor != null -> {
                SalesRecordPage(recordEditor!!, vm, friend, onFollowUp = { r -> followUpEditor = FollowUp(customerId = r.customerId, title = "跟进：${r.title}", dueDate = "", done = false, recordId = r.id) }) { recordEditor = null }
            }
            taskCenter -> { BackHandler { taskCenter = false }; SalesTaskScreen(vm, onRecord = { recordEditor = it }) { taskCenter = false } }
            crmOverview && customerDetail.isBlank() -> { BackHandler { crmOverview = false }; CrmOverviewScreen(vm, { customerDetail = it }, back = { crmOverview = false }, initialTab = crmTab) }
            cloudSync && accountViewModel != null -> SalesCloudSyncScreen(friend, vm, accountViewModel) { cloudSync = false }
            market -> SalesMarketScreen(friend, { kind, query, index -> (cloudAccount ?: error("请登录云账号")).authorizedGet("/api/v2/catalog/$kind", mapOf("q" to query, "page" to index.toString()), friend.accountScope) }) { market = false }
            models -> ModelScreen(d, vm, friend) { models = false }
            experts -> SalesExpertScreen(friend, vm, onLibrary, onBack = { experts = false; expertStartSection = "home" }, initialSection = expertStartSection)
            migration -> SalesMigrationImportScreen(friend, vm) { migration = false }
            customerDetail.isNotBlank() -> {
                val customer = d.customers.find { it.id == customerDetail }
                if (customer != null) SalesCrmScreen(customer, vm,
                    onBack = { customerDetail = "" }, onEdit = { customerEditor = customer },
                    onCapture = { vm.customerId = customer.id; customerDetail = ""; page = 2 },
                    onRecord = { recordEditor = it }, onImportSms = { smsCustomer = customer })
                else { BackHandler { customerDetail = "" }; SalesEmpty("客户已移除", "沟通记录仍可在工作台查看。"); XfButton("返回客户", { customerDetail = "" }, kind = XfButtonKind.Ghost, small = true) }
            }
            page == 1 -> {
                Box(Modifier.fillMaxSize().padding(bottom = inset.calculateBottomPadding())) {
                    CrmOverviewScreen(vm, { customerDetail = it }, { page = 0 }, embedded = true,
                        onAdd = { customerEditor = Customer(name = "") },
                        onContacts = { contacts.launch(Manifest.permission.READ_CONTACTS) },
                        onText = { textFile.launch(arrayOf("text/*")) },
                        onImages = { image.launch(arrayOf("image/*")) })
                }
            }
            else -> {
                val enter = remember(page) { Animatable(0f) }
                LaunchedEffect(page) { enter.animateTo(1f, tween(220, easing = LinearEasing)) }
                LazyColumn(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24.dp.toPx() },
                    contentPadding = PaddingValues(start = XfDimens.pagePadding, top = XfDimens.rowGap, end = XfDimens.pagePadding, bottom = inset.calculateBottomPadding() + XfDimens.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
                    item { SalesPageHeader(page, appearance, titles, subtitles, colors, onTasks = { taskCenter = true }, onBackAppearance = { appearance = false }) }
                    if (page == 4 && appearance) {
                        item { XfAppearancePanel() }
                    } else when (page) {
                        0 -> {
                            val today = java.time.LocalDate.now().toString()
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    SalesMetric("今日跟进", d.followUps.count { !it.done && it.dueDate == today }.toString(), Modifier.weight(1f)) { crmTab = 1; crmOverview = true }
                                    SalesMetric("待分析", d.records.count { it.report.isBlank() }.toString(), Modifier.weight(1f)) { page = 2 }
                                    SalesMetric("进行中商机", d.opportunities.count { it.stage !in listOf("已成交", "已流失") }.toString(), Modifier.weight(1f)) { crmTab = 2; crmOverview = true }
                                }
                            }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    XfButton("记录沟通", { page = 2 }, modifier = Modifier.fillMaxWidth(), icon = XfIcons.Mic)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                        QuickTile(XfIcons.File, "录音文件", !blocked, Modifier.weight(1f)) { audio.launch(arrayOf("audio/*")) }
                                        QuickTile(Icons.Outlined.Image, "沟通截图", !blocked, Modifier.weight(1f)) { image.launch(arrayOf("image/*")) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                        QuickTile(XfIcons.Mic, "接管通话录音", !blocked, Modifier.weight(1f)) { callRecordings = true }
                                        QuickTile(XfIcons.Edit, "文字笔记", !blocked, Modifier.weight(1f)) { Communication(title = "沟通笔记", customerId = vm.customerId).let { vm.saveRecord(it); recordEditor = it } }
                                    }
                                }
                            }
                            val due = d.followUps.filter { !it.done && it.dueDate.isNotBlank() && it.dueDate <= today }
                                .sortedWith(compareBy<FollowUp> { it.dueDate }.thenBy { it.dueTime.ifBlank { "23:59" } }).take(4)
                            item { XfSectionHeader("今日跟进", subtitle = "今天到期与已逾期", action = "查看全部", onAction = { crmTab = 1; crmOverview = true }) }
                            if (due.isEmpty()) item { SalesSectionEmpty("今天没有到期的跟进，可从客户档案创建下一步行动。") }
                            else item {
                                XfCard(padding = 0.dp) {
                                    due.forEachIndexed { index, task ->
                                        Column(Modifier.padding(horizontal = XfDimens.cardPaddingCompact)) {
                                            val overdue = task.dueDate < today
                                            XfListRow(
                                                title = task.title,
                                                subtitle = (d.customers.find { it.id == task.customerId }?.name ?: "未关联客户") + " · " + customerDateLabel(task.dueDate),
                                                leading = { XfIconTile(XfIcons.Clock, size = 34.dp, tone = if (overdue) XfTone.Warning else XfTone.Neutral) },
                                                trailing = {
                                                    XfStatusChip(if (overdue) "已逾期" else "待跟进", tone = if (overdue) XfTone.Warning else XfTone.Accent, modifier = Modifier.padding(end = 6.dp))
                                                    Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                                                },
                                                onClick = { if (d.customers.any { it.id == task.customerId }) customerDetail = task.customerId else followUpEditor = task },
                                            )
                                        }
                                        if (index != due.lastIndex) Hr()
                                    }
                                }
                            }
                            val pending = d.records.filter { it.report.isBlank() }.sortedByDescending { it.created }.take(3)
                            item { XfSectionHeader("待分析沟通", subtitle = "录音、截图或文字待生成复盘", action = "查看全部", onAction = { page = 2 }) }
                            if (pending.isEmpty()) item { SalesSectionEmpty("没有待分析的沟通。") }
                            else items(pending, key = { "pending-" + it.id }) { r -> RecordCard(r, d.customers.find { it.id == r.customerId }?.name) { recordEditor = r } }
                            val opportunities = d.opportunities.filter { it.stage !in listOf("已成交", "已流失") }.sortedBy { it.expectedDate.ifBlank { "9999" } }.take(3)
                            item { XfSectionHeader("进行中商机", subtitle = "按预计成交日期排序", action = "查看全部", onAction = { crmTab = 2; crmOverview = true }) }
                            if (opportunities.isEmpty()) item { SalesSectionEmpty("暂无进行中商机，可在客户档案中创建。") }
                            else item {
                                XfCard(padding = 0.dp) {
                                    opportunities.forEachIndexed { index, o ->
                                        Column(Modifier.padding(horizontal = XfDimens.cardPaddingCompact)) {
                                            XfListRow(
                                                title = o.title,
                                                subtitle = listOf(d.customers.find { it.id == o.customerId }?.name ?: "未关联客户", if (o.amount.isBlank()) "金额待确认" else "¥ ${o.amount}").joinToString(" · "),
                                                leading = { XfIconTile(XfIcons.Trending, size = 34.dp) },
                                                trailing = { XfStatusChip(o.stage, tone = salesTone(o.stage), modifier = Modifier.padding(end = 6.dp)); Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                                                onClick = { if (o.customerId.isNotBlank()) customerDetail = o.customerId },
                                            )
                                        }
                                        if (index != opportunities.lastIndex) Hr()
                                    }
                                }
                            }
                            item { XfSearchField(search, { search = it }, placeholder = "搜索沟通、原文或报告") }
                            item { XfSectionHeader("最近复盘", action = "查看全部", onAction = { page = 2 }) }
                            val records = d.records.sortedByDescending { it.created }.filter { (it.title + it.text + it.report).contains(search, true) }
                            if (records.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Chat, "暂无沟通记录", "录音、截图或文字，选择适合你的方式开始。") } }
                            items(records.take(5), key = { it.id }) { r -> RecordCard(r, d.customers.find { it.id == r.customerId }?.name) { recordEditor = r } }
                        }
                        2 -> {
                            item { MenuCard("专家与团队", "选择复盘专家、管理团队与分析设置") { page = 3 } }
                            item { ChoiceRow("关联客户", listOf("" to "暂不关联") + d.customers.map { it.id to it.name }, vm.customerId, !blocked) { vm.customerId = it } }
                            item {
                                XfCard(padding = XfDimens.cardPadding) {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(XfDimens.cardPadding)) {
                                        XfStatusChip(
                                            when { !vm.recording -> "准备就绪"; vm.recordingPaused -> "已暂停"; else -> "正在录音" },
                                            tone = when { !vm.recording -> XfTone.Neutral; vm.recordingPaused -> XfTone.Warning; else -> XfTone.Accent },
                                            icon = if (vm.recordingPaused) XfIcons.Pause else XfIcons.Mic,
                                        )
                                        Text("%02d:%02d".format(vm.seconds / 60, vm.seconds % 60), style = XfType.display.copy(fontWeight = FontWeight.Light), color = colors.ink)
                                        XfAvatar(icon = if (vm.recordingPaused) XfIcons.Pause else XfIcons.Mic, size = 56.dp)
                                        Text("录音保存在此手机", style = XfType.meta, color = colors.ink3)
                                        if (vm.recording) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
                                                XfButton(if (vm.recordingPaused) "继续录音" else "暂停录音", vm::toggleRecordingPause, modifier = Modifier.weight(1f), kind = XfButtonKind.Secondary, icon = if (vm.recordingPaused) XfIcons.Play else XfIcons.Pause)
                                                XfButton("结束录音", vm::stopRecording, modifier = Modifier.weight(1f), icon = XfIcons.Stop)
                                            }
                                        } else {
                                            XfButton("开始录音", { microphone.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !vm.busy && !friend.busy, modifier = Modifier.fillMaxWidth(), icon = XfIcons.Mic)
                                        }
                                        Text(if (vm.recordingPaused) "继续后接着记录，结束后保存本条录音" else "现场录音最长 10 分钟；较长录音可导入文件", style = XfType.small, color = colors.ink3)
                                    }
                                }
                            }
                            item {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap), verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    XfButton("导入录音", { audio.launch(arrayOf("audio/*")) }, kind = XfButtonKind.Secondary, small = true, enabled = !blocked)
                                    XfButton("导入截图", { image.launch(arrayOf("image/*")) }, kind = XfButtonKind.Secondary, small = true, enabled = !blocked)
                                    XfButton("导入文本", { textFile.launch(arrayOf("text/*")) }, kind = XfButtonKind.Secondary, small = true, enabled = !blocked)
                                    XfButton("写笔记", { Communication(title = "沟通笔记", customerId = vm.customerId).let { vm.saveRecord(it); recordEditor = it } }, kind = XfButtonKind.Secondary, small = true, enabled = !blocked)
                                }
                            }
                            item { XfSectionHeader("选择复盘方式") }
                            item { ExecutionChoice(vm, friend.store!!, !blocked) }
                            item {
                                XfCard(padding = 0.dp, onClick = if (!blocked) { { page = 3 } } else null) {
                                    XfListRow(title = "专家与分析设置", subtitle = d.mode, leading = { XfIconTile(XfIcons.Sparkles, size = 36.dp) }, trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) })
                                }
                            }
                            item {
                                XfCard(padding = XfDimens.cardPaddingCompact) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(vm.autoAnalyze, { vm.autoAnalyze = it }, enabled = !blocked)
                                        Spacer(Modifier.width(XfDimens.rowGap))
                                        Text("停止后发送本条录音、客户背景及所选资料，自动转写分析", Modifier.weight(1f), style = XfType.small, color = colors.ink2)
                                    }
                                }
                            }
                            item { Text("停止后录音保存在本机，原文可校正。电话双方录音请导入系统保存的文件。分析建议需由你确认后创建跟进。", style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 4.dp)) }
                            item { XfSectionHeader("选择沟通，生成报告") }
                            if (d.records.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Chat, "报告从沟通开始", "导入录音、截图或文字，开始第一次复盘。") } }
                            items(d.records.sortedByDescending { it.created }, key = { it.id }) { r -> RecordCard(r, d.customers.find { it.id == r.customerId }?.name) { recordEditor = r } }
                        }
                        3 -> {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    XfButton("导入专家", { desktopImport = true }, modifier = Modifier.weight(1f), kind = XfButtonKind.Secondary, icon = XfIcons.File)
                                    XfButton("创建专家", { experts = true }, modifier = Modifier.weight(1f), icon = XfIcons.Plus)
                                }
                            }
                            item { AccentActionCard("AI 帮我创建专家", "从工作目标开始，生成并编辑专家职责", XfIcons.Sparkles) { expertStartSection = "designer"; experts = true } }
                            item { SalesExpertLibrary(friend.store!!, vm, !blocked, onUse = { page = 2 }, onManage = { experts = true }) }
                            item { MenuCard("专家商店与技能市场", "发现并导入电脑版同源资源") { market = true } }
                            item { MenuCard("管理团队、技能与知识", "配置协作成员与销售参考资料", onLibrary) }
                            item { ChoiceRow("分析模式", salesModes.keys.map { it to it }, d.mode, !blocked) { value -> vm.update { it.copy(mode = value) } } }
                        }
                        4 -> {
                            item {
                                XfCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        XfStarMark(size = 56.dp)
                                        Spacer(Modifier.width(XfDimens.cardPadding))
                                        Column(Modifier.weight(1f)) {
                                            Text("Xsalers", style = XfType.h1, color = colors.ink)
                                            Text("你的专业销售工作空间", style = XfType.small, color = colors.ink2)
                                            Spacer(Modifier.height(XfDimens.rowGap))
                                            SalesStatus(if (d.useTrial) "AI 试用 · ${vm.quota?.let { "剩余 $it 次" } ?: "五次体验"}" else "已使用自己的模型")
                                        }
                                    }
                                }
                            }
                            item {
                                XfCard(padding = 4.dp) {
                                    SalesSettingRow(XfIcons.Sparkles, "专家与分析设置", "专家、团队、技能市场与分析模式") { page = 3 }
                                    Hr()
                                    SalesSettingRow(XfIcons.Users, "销售专家管理", "自己创建、使用模板或让 AI 设计") { experts = true }
                                    Hr()
                                    SalesSettingRow(XfIcons.Tasks, "后台任务", "处理进度 / 断点重试 / 失败原因") { taskCenter = true }
                                    Hr()
                                    SalesSettingRow(XfIcons.Chart, "个人 CRM", "全部商机、跟进与客户导出") { crmOverview = true }
                                }
                            }
                            item { ApplicationUpdateCard() }
                            if (accountViewModel?.account != null) item { CloudRoomsPanel(accountViewModel.account!!) }
                            item {
                                XfCard(padding = 4.dp) {
                                    SalesSettingRow(XfIcons.Refresh, "账号与云同步", "客户、复盘、专家和模型配置跨设备同步") { if (accountViewModel != null) cloudSync = true else vm.error = "请登录云账号后使用同步" }
                                    Hr()
                                    SalesSettingRow(XfIcons.Cpu, "模型配置", "对话 / 视觉 / ASR / TTS 与声音设置") { models = true }
        SalesSettingRow(XfIcons.Mic, "通话录音接管", "接管系统通话录音 · 转写后清理源文件") { callRecordings = true }
                                    Hr()
                                    SalesSettingRow(XfIcons.Monitor, "连接电脑", "连接桌面销售工作空间与远程能力", onDesktop)
                                    Hr()
                                    SalesSettingRow(XfIcons.Folder, "导入电脑版资源", "专家、团队、技能、知识与模型配置") { desktopImport = true }
                                    Hr()
                                    SalesSettingRow(XfIcons.Inbox, "迁移导入", "将已有工作资料迁移到当前销售空间") { migration = true }
                                    friend.store?.portablePackages?.forEach { (id, installed) ->
                                        Hr()
                                        SalesSettingRow(XfIcons.Export, "导出桌面专家包", org.json.JSONObject(installed.manifestJson).optJSONArray("experts")?.objects()?.take(3)?.joinToString("、") { it.optString("name") }.orEmpty()) { exportPack = id }
                                    }
                                }
                            }
                            item {
                                XfCard(padding = 4.dp) {
                                    SalesSettingRow(XfIcons.Blocks, "管理团队 / 技能与知识", "团队协作、专家资料与知识资源", onLibrary)
                                    Hr()
                                    SalesSettingRow(XfIcons.Chat, "销售专家对话与平台连接", "进入专家工作空间与平台设置", onLibrary)
                                    Hr()
                                    SalesSettingRow(XfIcons.Brand, "会员与订阅", "查看试用说明与模型使用方式") { subscription = true }
                                    Hr()
                                    SalesSettingRow(XfIcons.Shield, "电话状态保护", if (phoneGranted) "已授权 · 来电停止麦克风录音" else "授权后在来电时停止录音") { phone.launch(Manifest.permission.READ_PHONE_STATE) }
                                    Hr()
                                    SalesSettingRow(XfIcons.Palette, "外观", "跟随系统 / 浅色 / 深色与 5 组主色") { appearance = true }
                                }
                            }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    XfButton("退出登录", { signOut = true }, kind = XfButtonKind.Danger, modifier = Modifier.fillMaxWidth())
                                    Text("本机资料保留在当前账号的独立空间", style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 4.dp))
                                }
                            }
                            item { Text("通讯录和短信只在你选择导入时读取。分析仅发送所选记录、客户背景及专家资料；原始录音与客户数据保存在当前账号的应用私有目录。", style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 4.dp)) }
                        }
                    }
                }
            }
        }
    } }
    if (exportPack.isNotBlank()) {
        var password by remember(exportPack) { mutableStateOf("") }
        var includeSecrets by remember(exportPack) { mutableStateOf(false) }
        XfDialog(
            title = "导出桌面兼容专家包",
            onDismiss = { exportPack = "" },
            confirmLabel = "选择保存位置",
            onConfirm = { exportOptions = Triple(exportPack, password, includeSecrets); exportPack = ""; password = ""; exportFile.launch("Xsalers-专家包.xfpack") },
            confirmEnabled = !blocked && (!includeSecrets || password.length >= 8),
        ) {
            Text("保留此包的专家、团队、文件和模型设置，可在电脑版导入。", style = XfType.small, color = xf.ink2)
            Input("加密密码（可选）", password, { password = it }, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeSecrets, { includeSecrets = it }); Text("包含此包导入时保存的模型密钥", Modifier.weight(1f), style = XfType.small) }
            if (includeSecrets) Text("包含密钥时，请设置至少 8 位密码。", style = XfType.small, color = xf.ink3)
        }
    }
    importFile?.let { uri ->
        var password by remember(uri) { mutableStateOf("") }
        XfDialog(
            title = "导入电脑版专家包",
            onDismiss = { importFile = null },
            confirmLabel = "读取并预览",
            onConfirm = { vm.importBundle(uri, password); password = ""; importFile = null },
        ) {
            Text("支持 .xfpack、.xpack 和旧版 JSON。加密包请填写导出时设置的密码，未加密可留空。", style = XfType.small, color = xf.ink2)
            Input("包密码（可选）", password, { password = it }, secret = true)
        }
    }
    vm.portableBundle?.let { pack ->
        var secrets by remember(pack) { mutableStateOf(false) }
        var skills by remember(pack) { mutableStateOf(false) }
        val manifest = remember(pack) { pack.manifest() }
        val resources = manifest.optJSONArray("experts")?.objects().orEmpty()
        val providers = manifest.optJSONArray("providers")?.length() ?: 0
        XfDialog(
            title = "确认导入资源",
            onDismiss = { vm.dismissBundle() },
            confirmLabel = "导入",
            onConfirm = { vm.acceptBundle(friend, secrets, skills) },
            confirmEnabled = !blocked,
        ) {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
                item { Text("${resources.count { it.optString("kind") != "team" }} 位专家 · ${resources.count { it.optString("kind") == "team" }} 个团队 · $providers 项模型配置", style = XfType.small, color = xf.ink2) }
                items(resources) { Text(it.optString("name"), style = XfType.bodyStrong, color = xf.ink) }
                if (pack.secrets().isNotEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(secrets, { secrets = it }); Text("保存包内模型密钥到本机", Modifier.weight(1f), style = XfType.small) } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(skills, { skills = it }); Text("已核对来源，启用包内技能说明", Modifier.weight(1f), style = XfType.small) } }
                item { Text("桌面专属工具和远程引擎会保留配置，手机不会自动执行附带脚本。重复导入会更新同一包的资源。", style = XfType.meta, color = xf.ink3) }
            }
        }
    }
    if (screenshotSelection.isNotEmpty()) SalesScreenshotSelection(screenshotSelection, { screenshotSelection = it }, { vm.importImages(screenshotSelection); screenshotSelection = emptyList(); page = 2 })
    if (signOut) XfDialog(
        title = "退出当前账号？",
        onDismiss = { signOut = false },
        confirmLabel = "退出登录",
        onConfirm = { signOut = false; onLogout() },
        danger = true,
    ) {
        Text("当前账号的本机资料会保留。重新登录后可继续使用。", style = XfType.small, color = xf.ink2)
    }
    if (subscription) XfDialog(
        title = "会员与订阅",
        onDismiss = { subscription = false },
        confirmLabel = "配置模型",
        onConfirm = { subscription = false; models = true },
        dismissLabel = "了解了",
    ) {
        Text("订阅服务尚未上线。当前可使用五次 AI 任务试用，或配置自己的模型。专家生成、修改生成和测试会消耗试用次数；沟通分析包含转写 / 识图和报告，语音播报单独使用一次。", style = XfType.small, color = xf.ink2)
    }
    smsCustomer?.let { c ->
        XfDialog(
            title = "导入 ${c.name} 的短信",
            onDismiss = { smsCustomer = null },
            confirmLabel = "授权并导入",
            onConfirm = { sms.launch(Manifest.permission.READ_SMS) },
        ) {
            Text("仅导入与 ${c.phone} 匹配的最近 100 条短信，保存在本机供你确认。Android 可能限制此权限，届时可用文本文件导入。", style = XfType.small, color = xf.ink2)
        }
    }
    customerEditor?.let { c -> CustomerDialog(c, !blocked, { customerEditor = null }, { vm.saveCustomer(it); customerEditor = null }, { vm.deleteCustomer(c.id); customerEditor = null; customerDetail = "" }) }
    followUpEditor?.let { draft -> SalesFollowUpEditor(draft, vm, { followUpEditor = null }) }
    if (vm.contactPreview.isNotEmpty()) ContactsDialog(vm)
    if (desktopImport) DesktopImportDialog(vm, { desktopImport = false }, { bundleFile.launch(arrayOf("*/*")); desktopImport = false })
    vm.bundle?.let { b ->
        var include by remember(b) { mutableStateOf(false) }
        XfDialog(
            title = "确认导入电脑版资源",
            onDismiss = { if (!blocked) vm.bundle = null },
            confirmLabel = "导入",
            onConfirm = { vm.acceptBundle(friend, include) },
            confirmEnabled = !blocked,
        ) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                Text("${b.experts.size} 位专家 · ${b.teams.size} 个团队\n${b.skills.size} 个技能 · ${b.knowledge.size} 份知识\n${b.profiles.size} 项模型配置\n重复导入会更新同一资源，手机已有的其他资源会保留。", style = XfType.small, color = xf.ink2)
                if (b.secretCount > 0) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(include, { include = it }); Text("同时保存包内 ${b.secretCount} 项密钥", Modifier.weight(1f), style = XfType.small) }
                b.warnings.forEach { Text(it, style = XfType.meta, color = xf.dangerText, modifier = Modifier.padding(top = XfDimens.rowGap)) }
            }
        }
    }
}

/** 壳层页头：标题 + 副标题 + 右侧操作。「外观」子页在左侧显示返回按钮。 */
@Composable private fun SalesPageHeader(page: Int, appearance: Boolean, titles: List<String>, subtitles: List<String>, colors: XfColors, onTasks: () -> Unit, onBackAppearance: () -> Unit) {
    if (page == 4 && appearance) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XfIconButton(XfIcons.ChevRight, "返回我的", onBackAppearance, rotation = 180f)
            Spacer(Modifier.width(4.dp))
            XfPageHeader(title = "外观", subtitle = "主题模式与主色", modifier = Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (page == 0) Text(salesTodayMeta(), style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
            XfPageHeader(title = titles[page], subtitle = subtitles[page], actions = {
                if (page == 0) XfIconButton(XfIcons.Tasks, "任务中心", onTasks)
                Spacer(Modifier.width(6.dp))
                XfBrandTile(ProductBrand.icon, 34.dp)
            })
        }
    }
}

/** 任务进行中卡片：真实进度可估算时显示 XfProgressBar，否则显示不确定进度。 */
@Composable private fun SalesActiveTaskCard(task: SalesTask, record: Communication?, onOpen: () -> Unit) {
    val colors = xf
    val percent = salesEstimatedProgress(task, record)
    XfCard(padding = XfDimens.cardPaddingCompact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XfIconTile(XfIcons.Refresh, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (percent != null) "正在处理 · 预计 $percent%" else "正在处理", style = XfType.bodyStrong, color = colors.ink)
                Spacer(Modifier.height(6.dp))
                XfProgressBar(percent?.div(100f))
            }
            XfLink("查看任务", onClick = onOpen)
        }
    }
}

private fun salesTodayMeta(): String = runCatching {
    val now = java.time.LocalDateTime.now()
    val greeting = when (now.hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..18 -> "下午好"
        else -> "晚上好"
    }
    greeting + " · " + now.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA))
}.getOrDefault("")

internal fun salesTone(label: String): XfTone = when {
    label.contains("成交") || label.contains("完成") -> XfTone.Success
    label.contains("流失") || label.contains("逾期") || label.contains("失败") -> XfTone.Danger
    label.contains("待") || label.contains("暂停") || label.contains("试用") -> XfTone.Warning
    label.contains("跟进") || label.contains("进行") || label.contains("需求") || label.contains("报价") || label.contains("方案") -> XfTone.Accent
    else -> XfTone.Neutral
}

@Composable private fun SalesMetric(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    XfCard(modifier = modifier, onClick = onClick, padding = XfDimens.cardPaddingCompact) {
        Text(value, style = XfType.h1, color = xf.accent)
        Spacer(Modifier.height(2.dp))
        Text(label, style = XfType.meta, color = xf.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun SalesStatus(label: String) {
    XfStatusChip(label, tone = salesTone(label))
}

@Composable internal fun SalesEmpty(title: String, hint: String) {
    XfEmptyState(icon = XfIcons.Inbox, title = title, body = hint)
}

@Composable private fun SalesSectionEmpty(text: String) {
    XfCard(padding = XfDimens.cardPaddingCompact) {
        Text(text, style = XfType.small, color = xf.ink3)
    }
}

@Composable private fun QuickTile(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    XfCard(modifier = modifier.alpha(if (enabled) 1f else .5f), onClick = if (enabled) onClick else null, padding = 10.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            XfIconTile(icon, size = 32.dp)
            Text(label, style = XfType.label, color = xf.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun AccentActionCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = xf
    XfSurface(modifier = Modifier.fillMaxWidth(), color = colors.accentSoft, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(XfDimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            XfIconTile(icon, size = 36.dp)
            Spacer(Modifier.width(XfDimens.itemGap))
            Column(Modifier.weight(1f)) {
                Text(title, style = XfType.h3, color = colors.ink)
                Text(subtitle, style = XfType.meta, color = colors.accentInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.accentInk)
        }
    }
}

@Composable private fun SalesSettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    XfListRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.padding(horizontal = 12.dp),
        leading = { XfIconTile(icon, size = 34.dp) },
        trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = xf.ink4) },
        onClick = onClick,
    )
}

@Composable internal fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    XfCard(padding = 0.dp, onClick = onClick) {
        XfListRow(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.padding(horizontal = XfDimens.cardPaddingCompact),
            trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = xf.ink4) },
        )
    }
}

@Composable internal fun SelectCard(title: String, desc: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = xf
    XfSurface(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else .55f), onClick = if (enabled) onClick else null) {
        Row(Modifier.fillMaxWidth().padding(XfDimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = XfType.h3, color = colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(desc, style = XfType.small, color = colors.ink3)
            }
            if (selected) XfStatusChip("已选择", tone = XfTone.Accent, icon = XfIcons.Check)
        }
    }
}

@Composable internal fun RecordCard(r: Communication, customer: String?, onClick: () -> Unit) {
    val kindIcon = when (r.kind) {
        "audio" -> XfIcons.Mic
        "image" -> Icons.Outlined.Image
        else -> XfIcons.Edit
    }
    val kindLabel = when (r.kind) { "audio" -> "录音"; "image" -> "截图"; else -> "文字" }
    val tone = if (r.report.isBlank()) XfTone.Warning else XfTone.Success
    XfCard(padding = 0.dp, onClick = onClick) {
        XfListRow(
            title = r.title,
            subtitle = listOf(customer ?: "未关联客户", if (r.report.isBlank()) "待分析" else r.mode + " · 已完成", kindLabel).joinToString(" · "),
            modifier = Modifier.padding(horizontal = XfDimens.cardPaddingCompact),
            leading = { XfIconTile(kindIcon, size = 40.dp, tone = tone) },
            trailing = {
                XfStatusChip(if (r.report.isBlank()) "待分析" else "已完成", tone = tone, modifier = Modifier.padding(end = 6.dp))
                Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = xf.ink4)
            },
            minHeight = 64.dp,
        )
    }
}

@Composable internal fun ChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = xf
    val current = options.find { it.first == selected }?.second ?: "请选择"
    Box {
        XfSurface(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else .55f), shape = RoundedCornerShape(XfDimens.controlRadius), color = colors.surface2, onClick = if (enabled) { { expanded = true } } else null) {
            Row(Modifier.fillMaxWidth().heightIn(min = XfDimens.touch).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = XfType.label, color = colors.ink2)
                Spacer(Modifier.width(8.dp))
                Text(current, Modifier.weight(1f), style = XfType.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(XfIcons.ChevDown, null, Modifier.size(16.dp), tint = colors.ink4)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = XfType.body) },
                    onClick = { expanded = false; onSelect(id) },
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
        }
    }
}

@Composable internal fun ExecutionChoice(vm: SalesViewModel, s: Store, enabled: Boolean) {
    val colors = xf
    Column(verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
            XfChip("专家分析", selected = !vm.teamMode, onClick = if (enabled) { { vm.teamMode = false } } else null)
            XfChip("团队协作", selected = vm.teamMode, onClick = if (enabled) { { vm.teamMode = true } } else null)
        }
        val choices = if (vm.teamMode) s.teams.map { it.id to it.name } else s.experts.map { it.id to it.name }
        val selected = if (vm.teamMode) vm.selectedTeam else vm.selectedExpert
        if (choices.isEmpty()) {
            Text("还没有可用的专家或团队，请先创建或导入。", style = XfType.small, color = colors.ink3)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
                items(choices, key = { it.first }) { (id, name) ->
                    XfCard(
                        modifier = Modifier.width(180.dp).alpha(if (enabled) 1f else .6f),
                        onClick = if (enabled) { { if (vm.teamMode) vm.selectedTeam = id else vm.selectedExpert = id } } else null,
                        padding = XfDimens.cardPaddingCompact,
                    ) {
                        XfIconTile(if (vm.teamMode) XfIcons.Users else XfIcons.Sparkles, size = 36.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(name, style = XfType.bodyStrong, color = colors.ink, minLines = 2, maxLines = 2)
                        Spacer(Modifier.height(XfDimens.rowGap))
                        XfStatusChip(if (selected == id) "已选择" else "轻点选择", tone = if (selected == id) XfTone.Accent else XfTone.Neutral)
                    }
                }
            }
        }
    }
}

@Composable private fun CustomerDialog(original: Customer, enabled: Boolean, close: () -> Unit, save: (Customer) -> Unit, delete: () -> Unit) {
    var role by remember { mutableStateOf(original.role) }; var email by remember { mutableStateOf(original.email) }; var wechat by remember { mutableStateOf(original.wechat) }; var source by remember { mutableStateOf(original.source) }; var tags by remember { mutableStateOf(original.tags) }; var priority by remember { mutableStateOf(original.priority) }; var nextContact by remember { mutableStateOf(original.nextContact) }; var validation by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(original.name) }; var phone by remember { mutableStateOf(original.phone) }; var company by remember { mutableStateOf(original.company) }; var stage by remember { mutableStateOf(original.stage) }; var notes by remember { mutableStateOf(original.notes) }; var confirmDelete by remember { mutableStateOf(false) }
    CustomerEditSheet(onDismissRequest = close, title = { Text("客户资料", style = XfType.h2) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
        Input("姓名", name, { name = it.take(100) }, enabled = enabled); Input("电话", phone, { phone = it.take(40) }, enabled = enabled); Input("公司", company, { company = it.take(100) }, enabled = enabled)
        ChoiceRow("阶段", listOf("跟进中", "已成交", "已流失").map { it to it }, stage, enabled) { stage = it }
        Input("职务", role, { role = it.take(100) }, enabled = enabled); Input("邮箱", email, { email = it.take(200) }, enabled = enabled); Input("微信", wechat, { wechat = it.take(100) }, enabled = enabled); Input("客户来源", source, { source = it.take(100) }, enabled = enabled); Input("标签（逗号分隔）", tags, { tags = it.take(500) }, enabled = enabled)
        ChoiceRow("优先级", listOf("重点", "普通", "低优先级").map { it to it }, priority, enabled) { priority = it }
        CustomerDateField("下次联系", nextContact, enabled) { nextContact = it }
        Input("背景与备注", notes, { notes = it.take(12000) }, false, enabled = enabled)
        if (validation.isNotBlank()) Text(validation, style = XfType.small, color = xf.dangerText)
        if (original.name.isNotBlank()) {
            XfButton(if (confirmDelete) "确认删除客户（保留沟通记录）" else "删除客户", { confirmDelete = true }, enabled = enabled && !confirmDelete, kind = XfButtonKind.Danger, small = true)
            if (confirmDelete) XfButton("执行删除", delete, enabled = enabled, kind = XfButtonKind.Danger, small = true)
        }
    } }, confirmButton = {
        XfButton("保存", {
            runCatching { requireSalesDate(nextContact) }.onSuccess {
                save(original.copy(name = name.trim(), phone = phone.trim(), company = company.trim(), stage = stage, notes = notes, role = role, email = email, wechat = wechat, source = source, tags = tags, priority = priority, nextContact = nextContact))
            }.onFailure { validation = it.message.orEmpty() }
        }, enabled = enabled && name.isNotBlank())
    }, dismissButton = { XfButton("取消", close, kind = XfButtonKind.Ghost, small = true) })
}

@Composable internal fun Input(label: String, value: String, update: (String) -> Unit, single: Boolean = true, secret: Boolean = false, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .55f)) {
        XfTextField(
            value = value,
            onValueChange = { if (enabled) update(it) },
            label = label,
            singleLine = single,
            minLines = if (single) 1 else 3,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable private fun ContactsDialog(vm: SalesViewModel) {
    var selected by remember(vm.contactPreview) { mutableStateOf<Set<String>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    val colors = xf
    XfDialog(
        title = "选择导入的联系人 · ${selected.size}",
        onDismiss = { vm.contactPreview = emptyList() },
        confirmLabel = "导入所选",
        onConfirm = { vm.acceptContacts(selected) },
        confirmEnabled = selected.isNotEmpty() && !vm.busy,
    ) {
        Text("只保存你勾选的客户，不上传整个通讯录。", style = XfType.small, color = colors.ink2)
        Input("搜索姓名或电话", search, { search = it })
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(vm.contactPreview.filter { (it.name + it.phone).contains(search, true) }, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { selected = if (c.id in selected) selected - c.id else selected + c.id }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(c.id in selected, null)
                    Column {
                        Text(c.name, style = XfType.bodyStrong, color = colors.ink)
                        Text(c.phone, style = XfType.meta, color = colors.ink3)
                    }
                }
            }
        }
    }
}

@Composable private fun DesktopImportDialog(vm: SalesViewModel, close: () -> Unit, file: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(context.getSharedPreferences("desktop_connection", 0).getString("address", "https://").orEmpty()) }
    var code by remember { mutableStateOf("") }
    var secrets by remember { mutableStateOf(false) }
    XfDialog(
        title = "从电脑版导入",
        onDismiss = close,
        confirmLabel = "连接并预览",
        onConfirm = { vm.fetchDesktop(address, code, secrets); code = ""; close() },
        confirmEnabled = code.isNotBlank() && !vm.busy,
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            Text("在电脑版启用手机连接，填写地址与配对码。先预览再导入；支持电脑版导出的 .xfpack 专家包和旧版 JSON 文件。", style = XfType.small, color = xf.ink2)
            Input("电脑连接地址", address, { address = it })
            Input("配对码", code, { code = it }, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(secrets, { secrets = it }); Text("请求导出本人配置的模型密钥", Modifier.weight(1f), style = XfType.small) }
            XfButton("选择电脑版导出文件", file, kind = XfButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun ModelScreen(d: SalesData, vm: SalesViewModel, friend: FriendViewModel, back: () -> Unit) {
    var editing by remember { mutableStateOf<ModelProfile?>(null) }
    var url by remember(d.trialUrl) { mutableStateOf(d.trialUrl) }
    var urlError by remember { mutableStateOf("") }
    val colors = xf
    BackHandler(onBack = back)
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(XfDimens.pagePadding, XfDimens.rowGap, XfDimens.pagePadding, 40.dp), verticalArrangement = Arrangement.spacedBy(XfDimens.cardPadding)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XfIconButton(XfIcons.ChevRight, "返回我的", back, rotation = 180f)
                Spacer(Modifier.width(4.dp))
                XfPageHeader(title = "模型配置", subtitle = "对话与图片分析使用所选对话模型；语音使用独立 ASR / TTS 模型。")
            }
        }
        item { SelectCard("免密钥体验", vm.quota?.let { "剩余 $it / 5 次 AI 任务" } ?: "五次体验 · 由服务端验证账号并计数", d.useTrial, !vm.busy) { vm.update { it.copy(useTrial = true) } } }
        item {
            XfCard {
                Input("试用服务 HTTPS 地址", url, { url = it })
                if (urlError.isNotBlank()) Text(urlError, style = XfType.small, color = colors.dangerText)
                Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                    XfButton("保存服务地址", { runCatching { requireSafeApiUrl(url.trim()) }.onSuccess { vm.update { it.copy(trialUrl = url.trim().trimEnd('/')) }; urlError = "" }.onFailure { urlError = it.message.orEmpty() } }, enabled = !vm.busy, kind = XfButtonKind.Secondary, small = true)
                    XfButton("刷新额度", vm::checkQuota, enabled = !vm.busy && d.trialUrl.isNotBlank(), kind = XfButtonKind.Ghost, small = true)
                }
                Text("试用服务未部署时不能调用；订阅入口已预留，当前没有收费操作。", style = XfType.meta, color = colors.ink3)
            }
        }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { XfSectionHeader("自己的模型", modifier = Modifier.weight(1f)); XfButton("添加", { editing = ModelProfile() }, kind = XfButtonKind.Ghost, small = true) } }
        if (d.profiles.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Cpu, "还没有模型配置", "从电脑版导入，或添加兼容 OpenAI 的模型服务。") } }
        items(d.profiles, key = { it.id }) { p ->
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfIconTile(XfIcons.Cpu, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = XfType.bodyStrong, color = colors.ink)
                        Text(p.model, style = XfType.meta, color = colors.ink3)
                    }
                    XfStatusChip(if (p.key.isBlank()) "待填写 Key" else "已配置", tone = if (p.key.isBlank()) XfTone.Warning else XfTone.Success)
                }
                Text(if (p.key.isBlank()) "API Key 待填写" else "API Key 已加密保存", style = XfType.meta, color = colors.ink3)
                Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                    XfButton(if (!d.useTrial && d.active == p.id) "已启用" else "使用此模型", { vm.update { it.copy(active = p.id, useTrial = false) }; friend.change { it.baseUrl = p.url; it.model = p.model; it.apiKey = p.key } }, enabled = !vm.busy && !friend.busy && p.key.isNotBlank(), kind = XfButtonKind.Secondary, small = true)
                    XfButton("配置", { editing = p }, enabled = !vm.busy, kind = XfButtonKind.Ghost, small = true)
                    XfButton("移除", { vm.update { it.copy(profiles = it.profiles.filterNot { profile -> profile.id == p.id }, active = if (it.active == p.id) "" else it.active) } }, enabled = !vm.busy, kind = XfButtonKind.Ghost, small = true)
                }
            }
        }
    }
    editing?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        var endpoint by remember(p.id) { mutableStateOf(p.url) }
        var model by remember(p.id) { mutableStateOf(p.model) }
        var key by remember(p.id) { mutableStateOf(p.key) }
        var asr by remember(p.id) { mutableStateOf(p.asr) }
        var tts by remember(p.id) { mutableStateOf(p.tts) }
        var voice by remember(p.id) { mutableStateOf(p.voice) }
        var style by remember(p.id) { mutableStateOf(p.style) }
        var validation by remember { mutableStateOf("") }
        var syncSecret by remember(p.id) { mutableStateOf(p.syncSecret) }
        CustomerEditSheet(onDismissRequest = { editing = null }, title = { Text("配置模型服务", style = XfType.h2) }, text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            Input("服务商 / 配置名称", name, { name = it.take(100) }); Input("Base URL", endpoint, { endpoint = it }); Input("API Key", key, { key = it.take(4096) }, secret = true)
            ChoiceRow("小米对话模型", listOf("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.5-pro", "mimo-v2.5").map { it to it }, model) { model = it }
            Input("对话 / 视觉模型（可自定义）", model, { model = it.take(200) }); Input("ASR 模型", asr, { asr = it.take(200) })
            ChoiceRow("TTS 模型", listOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone").map { it to it }, tts) { tts = it }
            Input("预置音色", voice, { voice = it.take(100) }); Input("播报风格 / 音色设计描述", style, { style = it.take(500) }, false)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(syncSecret, { syncSecret = it }); Text("加密同步此配置的 API Key", Modifier.weight(1f), style = XfType.small) }
            Text("默认只同步模型参数。开启后，此密钥会加密存储到同一账号空间，供已登录设备使用。", style = XfType.meta, color = xf.ink3)
            Text("ASR 与 TTS 按小米 Chat Completions 协议调用。其他服务商需要提供兼容接口。声音复刻在播报时选择授权样本。", style = XfType.meta, color = xf.ink3)
            if (validation.isNotBlank()) Text(validation, style = XfType.small, color = xf.dangerText)
        } }, confirmButton = {
            XfButton("保存", {
                runCatching { requireSafeApiUrl(endpoint.trim()); require(name.isNotBlank() && model.isNotBlank()) { "请填写名称和模型" } }.onSuccess {
                    val next = p.copy(name = name.trim(), url = endpoint.trim().trimEnd('/'), model = model.trim(), key = key.trim(), asr = asr.trim(), tts = tts, voice = voice, style = style, syncSecret = syncSecret)
                    vm.update { it.copy(profiles = it.profiles.filterNot { old -> old.id == p.id } + next) }
                    editing = null; key = ""
                }.onFailure { validation = it.message.orEmpty() }
            }, enabled = !vm.busy)
        }, dismissButton = { XfButton("取消", { key = ""; editing = null }, kind = XfButtonKind.Ghost, small = true) })
    }
}
