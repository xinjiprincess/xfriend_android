package com.singu.xfriend

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable internal fun CustomerExportButton(vm: SalesViewModel, ids: Set<String>, label: String = "导出客户 CSV") {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.exportCustomers(uri, selected) }
    XfButton(
        text = label,
        onClick = { selected = ids; export.launch("Xsalers-客户-${LocalDate.now()}.csv") },
        enabled = ids.isNotEmpty() && !vm.busy,
        kind = XfButtonKind.Ghost,
        small = true,
    )
}

/**
 * 客户列表 / 待办 / 商机 / 联系计划。
 * embedded = true 时作为「客户」tab 使用，页头带新建客户入口；
 * embedded = false 时作为「我的 → 个人 CRM」深链页面使用，带返回。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CrmOverviewScreen(vm: SalesViewModel, open: (String) -> Unit, back: () -> Unit, embedded: Boolean = false, initialTab: Int = 0, onAdd: () -> Unit = {}, onContacts: () -> Unit = {}, onText: () -> Unit = {}, onImages: () -> Unit = {}) {
    val d = vm.data ?: return
    val colors = xf
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val today = LocalDate.now().toString()
    fun name(id: String) = d.customers.find { it.id == id }?.name ?: "未关联客户"
    LazyColumn(
        modifier = if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(XfDimens.pagePadding, XfDimens.rowGap, XfDimens.pagePadding, 40.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            Column {
                if (!embedded) SalesBackButton("返回工作空间", back)
                XfPageHeader(
                    title = "客户",
                    subtitle = "关系有脉络，跟进有节奏",
                    actions = { if (embedded) XfIconButton(XfIcons.Plus, "新建客户", onAdd) },
                )
            }
        }
        item { XfSearchField(search, { search = it }, placeholder = "搜索姓名、公司、电话、备注") }
        item {
            XfSegmented(
                options = listOf("客户", "待办", "商机", "联系计划"),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }
        when (tab) {
            0 -> {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                        items(listOf("全部", "今日跟进", "待补资料", "跟进中", "已成交", "已流失")) { value ->
                            XfChip(value, selected = filter == value, onClick = { filter = value })
                        }
                    }
                }
                val customers = d.customers.filter { c ->
                    (c.name + c.company + c.phone + c.notes + c.tags + c.email + c.wechat + c.source).contains(search, true) && when (filter) {
                        "全部" -> true
                        "今日跟进" -> c.nextContact == today || d.followUps.any { it.customerId == c.id && !it.done && it.dueDate == today }
                        "待补资料" -> c.phone.isBlank() || c.company.isBlank()
                        else -> c.stage == filter
                    }
                }
                if (customers.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Users, "暂无匹配客户", "新建客户、导入资料，或调整筛选条件。") } }
                items(customers, key = { it.id }) { c ->
                    val next = d.followUps.filter { it.customerId == c.id && !it.done }.minWithOrNull(compareBy<FollowUp> { it.dueDate.ifBlank { "9999" } }.thenBy { it.dueTime })
                    CustomerRow(c, next) { open(c.id) }
                }
                if (embedded) item {
                    XfCard {
                        XfSectionHeader("导入客户", subtitle = "只保存你确认导入的联系人")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap), verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                            XfButton("通讯录", onContacts, enabled = !vm.busy && !vm.recording, kind = XfButtonKind.Secondary, small = true)
                            XfButton("文本", onText, enabled = !vm.busy && !vm.recording, kind = XfButtonKind.Secondary, small = true)
                            XfButton("截图", onImages, enabled = !vm.busy && !vm.recording, kind = XfButtonKind.Secondary, small = true)
                        }
                    }
                }
                item { CustomerExportButton(vm, customers.map { it.id }.toSet(), "导出筛选客户") }
            }
            1 -> {
                val tasks = d.followUps.filter { (it.title + name(it.customerId)).contains(search, true) }.sortedWith(compareBy<FollowUp> { it.done }.thenBy { it.dueDate.ifBlank { "9999" } }.thenBy { it.dueTime.ifBlank { "23:59" } })
                if (tasks.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Tasks, "暂无跟进", "在客户详情添加行动、截止日期和关联沟通。") } }
                items(tasks, key = { it.id }) { task ->
                    XfCard(padding = XfDimens.cardPaddingCompact) {
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(task.done, { vm.toggleFollowUp(task.id) }, enabled = !vm.busy)
                            Spacer(Modifier.width(XfDimens.rowGap))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(task.title, style = XfType.bodyStrong, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(name(task.customerId) + " · " + "${customerDateLabel(task.dueDate)} ${task.dueTime} · ${task.priority}", style = XfType.meta, color = colors.ink3)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    XfStatusChip(
                                        when { task.done -> "已完成"; !task.done && task.dueDate.isNotBlank() && task.dueDate < today -> "已逾期"; else -> "待跟进" },
                                        tone = when { task.done -> XfTone.Success; !task.done && task.dueDate.isNotBlank() && task.dueDate < today -> XfTone.Danger; else -> XfTone.Accent },
                                    )
                                    if (d.customers.any { it.id == task.customerId }) XfLink("查看客户并编辑", onClick = { open(task.customerId) })
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                item {
                    val openAmount = d.opportunities.filter { it.stage !in listOf("已成交", "已流失") }.fold(java.math.BigDecimal.ZERO) { sum, o -> sum + (o.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) }
                    val wonAmount = d.opportunities.filter { it.stage == "已成交" }.fold(java.math.BigDecimal.ZERO) { sum, o -> sum + (o.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) }
                    XfCard {
                        XfSectionHeader("商机总额")
                        XfKeyValueRow("未结商机", "¥ $openAmount", valueColor = colors.accent)
                        XfKeyValueRow("已成交", "¥ $wonAmount", valueColor = colors.successText)
                        Text("仅合计已填写的金额", style = XfType.meta, color = colors.ink3)
                    }
                }
                val opportunities = d.opportunities.filter { (it.title + name(it.customerId)).contains(search, true) }.sortedBy { it.expectedDate.ifBlank { "9999" } }
                if (opportunities.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Trending, "暂无商机", "从客户详情创建商机，跟踪金额、阶段与预计成交日期。") } }
                items(opportunities, key = { it.id }) { o ->
                    XfCard(padding = 0.dp, onClick = { if (o.customerId.isNotBlank()) open(o.customerId) }) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = XfDimens.cardPaddingCompact, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.Trending, size = 40.dp, tone = salesTone(o.stage))
                            Spacer(Modifier.width(XfDimens.itemGap))
                            Column(Modifier.weight(1f)) {
                                Text(o.title, style = XfType.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${name(o.customerId)} · ${o.stage} · ¥ ${o.amount.ifBlank { "待确认" }} · ${customerDateLabel(o.expectedDate)}", style = XfType.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(XfDimens.rowGap))
                            Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                        }
                    }
                }
            }
            3 -> {
                val customers = d.customers.filter { it.nextContact.isNotBlank() && (it.name + it.company + it.phone).contains(search, true) }.sortedBy { it.nextContact }
                if (customers.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Calendar, "暂无联系计划", "在客户资料中填写下次联系日期。") } }
                items(customers, key = { it.id }) { c ->
                    XfCard(padding = 0.dp, onClick = { open(c.id) }) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = XfDimens.cardPaddingCompact, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            XfAvatar(text = c.name, size = 40.dp)
                            Spacer(Modifier.width(XfDimens.itemGap))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = XfType.bodyStrong, color = colors.ink)
                                Text(customerDateLabel(c.nextContact) + " · " + c.priority, style = XfType.meta, color = colors.ink3)
                            }
                            if (c.nextContact < today) XfStatusChip("已逾期", tone = XfTone.Danger, modifier = Modifier.padding(end = 6.dp))
                            Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                        }
                    }
                }
            }
        }
        item { CustomerExportButton(vm, d.customers.map { it.id }.toSet(), "导出全部客户资料") }
    }
}

@Composable private fun CustomerRow(customer: Customer, next: FollowUp?, onClick: () -> Unit) {
    val colors = xf
    XfCard(padding = 0.dp, onClick = onClick) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = XfDimens.cardPaddingCompact, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            XfAvatar(text = customer.name, size = 44.dp)
            Spacer(Modifier.width(XfDimens.itemGap))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(customer.name, style = XfType.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(customer.company, customer.role).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "个人客户" }, style = XfType.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    next?.let { "${customerDateLabel(it.dueDate)} ${it.dueTime} · ${it.title}" } ?: customer.nextContact.takeIf { it.isNotBlank() }?.let { "下次联系 · ${customerDateLabel(it)}" } ?: "尚未安排跟进",
                    style = XfType.meta,
                    color = colors.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(XfDimens.rowGap))
            Column(horizontalAlignment = Alignment.End) {
                SalesStatus(customer.stage)
                Spacer(Modifier.height(6.dp))
                Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
            }
        }
    }
}
