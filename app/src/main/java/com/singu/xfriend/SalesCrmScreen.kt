package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 客户档案：Hero（头像/姓名/公司/阶段）+ 统计 + 分段 tabs（概览/沟通/待办/商机/资料）。
 * 所有编辑、核对、导出与 AI 建议流程保持原有确认语义。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SalesCrmScreen(customer: Customer, vm: SalesViewModel, onBack: () -> Unit, onEdit: () -> Unit, onCapture: () -> Unit, onRecord: (Communication) -> Unit, onImportSms: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = xf
    var tab by rememberSaveable(customer.id) { mutableIntStateOf(0) }
    var opportunityEditor by remember { mutableStateOf<Opportunity?>(null) }
    var followUpEditor by remember { mutableStateOf<FollowUp?>(null) }
    var insightEditor by remember { mutableStateOf<CustomerInsight?>(null) }
    val data = vm.data ?: return
    val liveCustomer = data.customers.find { it.id == customer.id } ?: return
    val opportunities = data.opportunities.filter { it.customerId == liveCustomer.id }
    val followUps = data.followUps.filter { it.customerId == liveCustomer.id }
    val records = data.records.filter { it.customerId == liveCustomer.id }.sortedByDescending { it.created }
    val blocked = vm.busy || vm.recording
    BackHandler(onBack = onBack)

    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(XfDimens.pagePadding, XfDimens.rowGap, XfDimens.pagePadding, 40.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.cardPadding),
    ) {
        item {
            Column {
                SalesBackButton("返回客户", onBack)
                XfPageHeader(title = "客户档案", subtitle = "关系有脉络，跟进有节奏")
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.Top) {
                    XfAvatar(text = liveCustomer.name, size = 64.dp)
                    Spacer(Modifier.width(XfDimens.cardPadding))
                    Column(Modifier.weight(1f)) {
                        Text(liveCustomer.name, style = XfType.h1, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(listOf(liveCustomer.company, liveCustomer.role).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "客户资料待完善" }, style = XfType.small, color = colors.ink2)
                        Spacer(Modifier.height(XfDimens.rowGap))
                        Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap), verticalAlignment = Alignment.CenterVertically) {
                            SalesStatus(liveCustomer.stage)
                            if (liveCustomer.nextContact.isNotBlank()) Text("下次联系 · ${customerDateLabel(liveCustomer.nextContact)}", style = XfType.meta, color = colors.ink3, maxLines = 1)
                        }
                    }
                    XfIconButton(XfIcons.Edit, "编辑客户资料", onEdit, enabled = !blocked)
                }
                Spacer(Modifier.height(XfDimens.itemGap))
                XfCardStat(
                    records = records.size,
                    opportunities = opportunities.size,
                    followUps = followUps.count { !it.done },
                )
                Spacer(Modifier.height(XfDimens.itemGap))
                XfButton("记录沟通", onCapture, enabled = !blocked, modifier = Modifier.fillMaxWidth(), icon = XfIcons.Mic)
                if (liveCustomer.phone.isNotBlank()) {
                    Spacer(Modifier.height(XfDimens.rowGap))
                    XfButton(
                        "拨打电话",
                        { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", liveCustomer.phone, null))) }.onFailure { vm.error = "设备没有拨号应用" } },
                        kind = XfButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                        small = true,
                    )
                }
            }
        }
        item {
            XfSegmented(
                options = listOf("概览", "沟通", "待办", "商机", "资料"),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }
        when (tab) {
            4 -> {
                item {
                    XfCard(padding = 0.dp, onClick = onEdit) {
                        XfListRow(
                            title = "编辑客户资料",
                            subtitle = "联系人、公司、标签、备注与下次联系",
                            modifier = Modifier.padding(horizontal = XfDimens.cardPaddingCompact),
                            leading = { XfIconTile(XfIcons.Edit, size = 36.dp) },
                            trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                        )
                    }
                }
                item { CustomerPortraitCard(liveCustomer.insight, !blocked) { insightEditor = liveCustomer.insight } }
                item {
                    XfCard {
                        XfSectionHeader("客户资料")
                        XfKeyValueRow("电话", liveCustomer.phone.ifBlank { "未填写" })
                        XfKeyValueRow("邮箱", liveCustomer.email.ifBlank { "未填写" })
                        XfKeyValueRow("微信", liveCustomer.wechat.ifBlank { "未填写" })
                        XfKeyValueRow("客户来源", liveCustomer.source.ifBlank { "未填写" })
                        XfKeyValueRow("标签", liveCustomer.tags.ifBlank { "未填写" })
                        XfKeyValueRow("优先级", liveCustomer.priority.ifBlank { "未填写" })
                        XfKeyValueRow("下次联系", customerDateLabel(liveCustomer.nextContact))
                        if (liveCustomer.notes.isNotBlank()) {
                            Spacer(Modifier.height(XfDimens.rowGap))
                            Text("背景与备注", style = XfType.label, color = colors.ink2)
                            Spacer(Modifier.height(4.dp))
                            Text(liveCustomer.notes, style = XfType.body, color = colors.ink)
                        }
                        Spacer(Modifier.height(XfDimens.itemGap))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap), verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                            CustomerExportButton(vm, setOf(liveCustomer.id), "导出此客户")
                            if (liveCustomer.phone.isNotBlank()) XfButton("导入短信", onImportSms, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                        }
                    }
                }
            }
            0 -> {
                item { CustomerPortraitCard(liveCustomer.insight, !blocked) { insightEditor = liveCustomer.insight } }
                val candidates = records.filter { CustomerAnalysis.read(it.analysisJson) != null }
                item { XfSectionHeader("沟通分析建议", subtitle = "仅在你核对并保存后写入客户资料") }
                if (candidates.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Sparkles, "暂无分析建议", "完成沟通分析后，在这里核对画像、下一步行动与商机估计。") } }
                items(candidates, key = { "candidate-${it.id}" }) { record ->
                    CustomerAnalysisCard(record, opportunities, !blocked,
                        onProfile = { insightEditor = liveCustomer.insight.withCandidate(it) },
                        onAction = { followUpEditor = FollowUp(customerId = liveCustomer.id, title = it.title.take(200),
                            dueDate = it.dueDate.takeIf { date -> runCatching { requireSalesDate(date) }.isSuccess }.orEmpty(),
                            recordId = record.id, priority = it.priority.takeIf { priority -> priority in listOf("低", "普通", "高", "紧急") } ?: "普通",
                            dueTime = it.dueTime.takeIf { time -> time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) }.orEmpty()) },
                        onProbability = { opportunityEditor = it }, onRecord = { onRecord(record) })
                }
            }
            1 -> {
                item { XfSectionHeader("沟通时间线", subtitle = "共 ${records.size} 次沟通") }
                if (records.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Chat, "还没有沟通", "为这位客户采集录音、截图或文字，逐步积累关系脉络。") } }
                items(records, key = { it.id }) { record ->
                    Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                        Text(Instant.ofEpochMilli(record.created).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd · HH:mm")), style = XfType.meta, color = colors.ink3)
                        RecordCard(record, liveCustomer.name) { onRecord(record) }
                        if (record.kind == "audio" && record.file.isNotBlank()) SalesPlayer(vm, record)
                        val summary = CustomerAnalysis.read(record.analysisJson)?.profile?.summary.orEmpty().ifBlank { record.report }
                        if (summary.isNotBlank()) Text(summary.take(240), maxLines = 4, overflow = TextOverflow.Ellipsis, style = XfType.small, color = colors.ink2)
                        if (record.attachments.isNotEmpty()) Text("${record.attachments.size} 项附件", style = XfType.meta, color = colors.ink3)
                    }
                }
            }
            3 -> {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XfSectionHeader("商机 · ${opportunities.size}", modifier = Modifier.weight(1f))
                        XfButton("新建商机", { opportunityEditor = Opportunity(customerId = liveCustomer.id, title = "") }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true, icon = XfIcons.Plus)
                    }
                }
                if (opportunities.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Trending, "下一项商机，待你发现", "记录已确认的机会、金额与阶段，未知信息可以留空。") } }
                items(opportunities, key = { it.id }) { opportunity ->
                    XfCard(onClick = { opportunityEditor = opportunity }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.Trending, size = 36.dp, tone = salesTone(opportunity.stage))
                            Spacer(Modifier.width(10.dp))
                            Text(opportunity.title, Modifier.weight(1f), style = XfType.h3, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                        }
                        Spacer(Modifier.height(XfDimens.itemGap))
                        Text(if (opportunity.amount.isBlank()) "金额待确认" else "¥ ${opportunity.amount}", style = XfType.h1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = colors.accent)
                        Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap), verticalAlignment = Alignment.CenterVertically) {
                            SalesStatus(opportunity.stage)
                            Text("预计成交 · ${customerDateLabel(opportunity.expectedDate)}", style = XfType.meta, color = colors.ink3)
                        }
                        if (opportunity.probabilityLow.isNotBlank()) {
                            Text("AI 成交估计 ${opportunity.probabilityLow}–${opportunity.probabilityHigh}% · 非承诺", style = XfType.small, color = colors.accentInk)
                            Text(opportunity.probabilityReason, style = XfType.meta, color = colors.ink3)
                            Text(opportunity.probabilityEvidence, style = XfType.meta, color = colors.ink3)
                        }
                        if (opportunity.notes.isNotBlank()) Text(opportunity.notes, style = XfType.small, color = colors.ink2)
                    }
                }
            }
            2 -> {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XfSectionHeader("待办 · ${followUps.count { !it.done }}", modifier = Modifier.weight(1f))
                        XfButton("新建跟进", { followUpEditor = FollowUp(customerId = liveCustomer.id, title = "") }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true, icon = XfIcons.Plus)
                    }
                }
                item { Text("行动由你确认。AI 分析不会自动创建或修改客户、商机和跟进。", style = XfType.meta, color = colors.ink3) }
                if (followUps.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Tasks, "留一个明确的下一步", "填写跟进事项、日期，或从已分析的沟通报告创建。") } }
                items(followUps.sortedWith(compareBy<FollowUp> { it.done }.thenBy { it.dueDate.ifBlank { "9999" } }.thenBy { it.dueTime.ifBlank { "23:59" } }.thenByDescending { listOf("低", "普通", "高", "紧急").indexOf(it.priority) }), key = { it.id }) { task ->
                    val overdue = !task.done && task.dueDate.isNotBlank() && runCatching { LocalDate.parse(task.dueDate).isBefore(LocalDate.now()) }.getOrDefault(false)
                    XfCard(padding = XfDimens.cardPaddingCompact) {
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(task.done, { vm.toggleFollowUp(task.id) }, enabled = !blocked)
                            Spacer(Modifier.width(XfDimens.rowGap))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(task.title, style = XfType.bodyStrong, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${customerDateLabel(task.dueDate)} ${task.dueTime} · ${task.priority}", style = XfType.meta, color = colors.ink3)
                                XfStatusChip(if (task.done) "已完成" else if (overdue) "已逾期" else "待跟进", tone = if (task.done) XfTone.Success else if (overdue) XfTone.Danger else XfTone.Accent)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    XfButton("编辑跟进", { followUpEditor = task }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                                    data.records.find { it.id == task.recordId }?.let { record -> XfButton("查看关联沟通", { onRecord(record) }, kind = XfButtonKind.Ghost, small = true) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    insightEditor?.let { CustomerInsightEditor(liveCustomer, it, vm) { insightEditor = null } }
    opportunityEditor?.let { SalesOpportunityEditor(it, vm) { opportunityEditor = null } }
    followUpEditor?.let { SalesFollowUpEditor(it, vm) { followUpEditor = null } }
}

@Composable private fun XfCardStat(records: Int, opportunities: Int, followUps: Int) {
    val colors = xf
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(records.toString() to "次沟通", opportunities.toString() to "项商机", followUps.toString() to "项待办").forEachIndexed { index, (value, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = XfType.h2, color = colors.ink)
                Text(label, style = XfType.meta, color = colors.ink3)
            }
            if (index != 2) Box(Modifier.width(1.dp).height(28.dp).background(colors.line))
        }
    }
}

@Composable private fun SalesOpportunityEditor(original: Opportunity, vm: SalesViewModel, onClose: () -> Unit) {
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var amount by rememberSaveable(original.id) { mutableStateOf(original.amount) }
    var stage by rememberSaveable(original.id) { mutableStateOf(original.stage) }
    var expectedDate by rememberSaveable(original.id) { mutableStateOf(original.expectedDate) }
    var probabilityLow by rememberSaveable(original.id) { mutableStateOf(original.probabilityLow) }
    var probabilityHigh by rememberSaveable(original.id) { mutableStateOf(original.probabilityHigh) }
    var probabilityReason by rememberSaveable(original.id) { mutableStateOf(original.probabilityReason) }
    var probabilityEvidence by rememberSaveable(original.id) { mutableStateOf(original.probabilityEvidence) }
    var notes by rememberSaveable(original.id) { mutableStateOf(original.notes) }
    var deleting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val exists = vm.data?.opportunities?.any { it.id == original.id } == true
    val dateValid = expectedDate.isBlank() || runCatching { requireSalesDate(expectedDate) }.isSuccess
    val amountValid = amount.isBlank() || amount.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))
    val blocked = vm.busy || vm.recording
    LaunchedEffect(vm.busy) { if (pending && !vm.busy) { pending = false; if (vm.error == null) onClose() } }
    CustomerEditSheet(onDismissRequest = { if (!blocked) onClose() }, title = { Text(if (exists) "编辑商机" else "新建商机", style = XfType.h2) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            Input("商机标题", title, { title = it.take(100) }, enabled = !blocked)
            Input("金额（人民币，可留空）", amount, { amount = it.take(40) }, enabled = !blocked)
            if (!amountValid) Text("请输入非负数字，最多两位小数", style = XfType.meta, color = xf.dangerText)
            ChoiceRow("商机阶段", listOf("跟进中", "需求确认", "方案沟通", "报价谈判", "已成交", "已流失").map { it to it }, stage, !blocked) { stage = it }
            CustomerDateField("预计成交", expectedDate, !blocked) { expectedDate = it }
            Text("AI 成交估计（0–100%，未经校准，非承诺）", style = XfType.label, color = xf.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                Box(Modifier.weight(1f)) { Input("下限 %", probabilityLow, { probabilityLow = it.take(6) }, enabled = !blocked) }
                Box(Modifier.weight(1f)) { Input("上限 %", probabilityHigh, { probabilityHigh = it.take(6) }, enabled = !blocked) }
            }
            Input("估计理由与未知项", probabilityReason, { probabilityReason = it.take(12000) }, enabled = !blocked)
            Input("沟通证据", probabilityEvidence, { probabilityEvidence = it.take(12000) }, false, enabled = !blocked)
            Input("背景与备注", notes, { notes = it.take(12000) }, false, enabled = !blocked)
            vm.error?.let { Text(it, style = XfType.small, color = xf.dangerText) }
            if (exists) XfButton("删除商机", { deleting = true }, enabled = !blocked, kind = XfButtonKind.Danger, small = true)
        }
    }, confirmButton = {
        XfButton(
            if (pending) "保存中…" else "确认保存",
            {
                pending = true
                vm.saveOpportunity(original.copy(title = title.trim(), amount = amount.trim(), stage = stage, expectedDate = expectedDate.trim(), notes = notes, probabilityLow = probabilityLow, probabilityHigh = probabilityHigh, probabilityReason = probabilityReason, probabilityEvidence = probabilityEvidence))
            },
            enabled = !blocked && title.isNotBlank() && dateValid && amountValid && validCustomerProbability(probabilityLow, probabilityHigh) && (probabilityLow.isBlank() || (probabilityReason.isNotBlank() && probabilityEvidence.isNotBlank())),
        )
    }, dismissButton = { XfButton("取消", onClose, enabled = !blocked, kind = XfButtonKind.Ghost, small = true) })
    if (deleting) XfDialog(
        title = "删除这项商机？",
        onDismiss = { deleting = false },
        confirmLabel = "删除",
        onConfirm = { deleting = false; pending = true; vm.deleteOpportunity(original.id) },
        dismissLabel = "保留",
        danger = true,
        confirmEnabled = !blocked,
    ) {
        Text("${original.title}\n客户和沟通记录会保留。", style = XfType.small, color = xf.ink2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SalesFollowUpEditor(original: FollowUp, vm: SalesViewModel, onClose: () -> Unit) {
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var customerId by rememberSaveable(original.id) { mutableStateOf(original.customerId) }
    var dueDate by rememberSaveable(original.id) { mutableStateOf(original.dueDate) }
    var recordId by rememberSaveable(original.id) { mutableStateOf(original.recordId) }
    var priority by rememberSaveable(original.id) { mutableStateOf(original.priority) }
    var dueTime by rememberSaveable(original.id) { mutableStateOf(original.dueTime) }
    var reminder by rememberSaveable(original.id) { mutableStateOf(original.reminder) }
    val reminderPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> reminder = granted; if (!granted) vm.error = "请在系统设置中允许通知，才能收到跟进提醒" }
    var done by rememberSaveable(original.id) { mutableStateOf(original.done) }
    var deleting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val data = vm.data ?: return
    val exists = data.followUps.any { it.id == original.id }
    val blocked = vm.busy || vm.recording
    val validDate = dueDate.isBlank() || runCatching { requireSalesDate(dueDate) }.isSuccess
    LaunchedEffect(vm.busy) { if (pending && !vm.busy) { pending = false; if (vm.error == null) onClose() } }
    CustomerEditSheet(onDismissRequest = { if (!blocked) onClose() }, title = { Text(if (exists) "编辑跟进" else "确认跟进行动", style = XfType.h2) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            Text("检查客户、事项和日期后再保存；此操作由你确认。", style = XfType.meta, color = xf.ink3)
            ChoiceRow("关联客户", listOf("" to "暂不关联") + data.customers.map { it.id to it.name }, customerId, !blocked) { customerId = it; recordId = "" }
            Input("跟进事项", title, { title = it.take(200) }, false, enabled = !blocked)
            CustomerDateField("截止日期", dueDate, !blocked) { dueDate = it; if (it.isBlank()) reminder = false }
            CustomerTimeField(dueTime, !blocked) { dueTime = it; if (it.isBlank()) reminder = false }
            ChoiceRow("优先级", listOf("低", "普通", "高", "紧急").map { it to it }, priority, !blocked) { priority = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(reminder, { checked -> if (checked && android.os.Build.VERSION.SDK_INT >= 33) reminderPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else reminder = checked }, enabled = !blocked && dueDate.isNotBlank() && dueTime.isNotBlank())
                Spacer(Modifier.width(XfDimens.rowGap))
                Text("到时提醒我", style = XfType.small, color = xf.ink2)
            }
            Text("系统节电设置可能延后提醒；打开应用后会重新检查待办。", style = XfType.meta, color = xf.ink3)
            ChoiceRow("关联沟通", listOf("" to "不关联沟通") + data.records.filter { it.customerId == customerId || it.customerId.isBlank() }.map { it.id to it.title }, recordId, !blocked) { recordId = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(done, { done = it }, enabled = !blocked); Spacer(Modifier.width(XfDimens.rowGap)); Text("标记为已完成", Modifier.weight(1f), style = XfType.small, color = xf.ink2) }
            vm.error?.let { Text(it, style = XfType.small, color = xf.dangerText) }
            if (exists) XfButton("删除跟进", { deleting = true }, enabled = !blocked, kind = XfButtonKind.Danger, small = true)
        }
    }, confirmButton = {
        XfButton(
            if (pending) "保存中…" else "确认保存跟进",
            { pending = true; vm.saveFollowUp(original.copy(customerId = customerId, title = title.trim(), dueDate = dueDate.trim(), done = done, recordId = recordId, priority = priority, dueTime = dueTime, reminder = reminder)) },
            enabled = !blocked && title.isNotBlank() && validDate,
        )
    }, dismissButton = { XfButton("取消", onClose, enabled = !blocked, kind = XfButtonKind.Ghost, small = true) })
    if (deleting) XfDialog(
        title = "删除这项跟进？",
        onDismiss = { deleting = false },
        confirmLabel = "删除",
        onConfirm = { deleting = false; pending = true; vm.deleteFollowUp(original.id) },
        dismissLabel = "保留",
        danger = true,
        confirmEnabled = !blocked,
    ) {
        Text(original.title, style = XfType.small, color = xf.ink2)
    }
}
