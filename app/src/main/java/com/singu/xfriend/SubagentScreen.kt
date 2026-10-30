package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class SubagentTaskGroup(val title: String, val empty: String) {
    NeedsYou("需要你", "暂无需要处理的任务"),
    Running("进行中", "暂无进行中的任务"),
    Waiting("等待外部", "暂无等待外部的任务"),
    Completed("已完成", "暂无已完成或已取消的任务"),
}

internal fun subagentTaskGroup(run: FriendRun): SubagentTaskGroup = when {
    run.state in setOf("requires_review", "interrupted", "failed") -> SubagentTaskGroup.NeedsYou
    run.active -> SubagentTaskGroup.Running
    run.state.startsWith("waiting_") -> SubagentTaskGroup.Waiting
    run.state in setOf("completed", "cancelled") -> SubagentTaskGroup.Completed
    else -> SubagentTaskGroup.NeedsYou
}

internal fun subagentTaskTitle(run: FriendRun): String = run.agentTitle.ifBlank {
    run.input.lineSequence().firstOrNull()?.trim()?.take(40).orEmpty().ifBlank { "任务 ${run.id.take(6)}" }
}

/** A context may have several explicit follow-up runs; keep its current execution visible. */
internal fun subagentTaskRows(runs: List<FriendRun>): List<FriendRun> = runs
    .groupBy { if (it.contextId.isNotBlank()) "context:${it.contextId}" else "run:${it.id}" }
    .values.map { attempts ->
        attempts.filter { it.state == "running" }.maxByOrNull { it.created }
            ?: attempts.filter { it.state == "queued" }.maxByOrNull { it.created }
            ?: attempts.maxBy { it.created }
    }.sortedByDescending { it.created }

internal fun subagentConversationTasks(runs: List<FriendRun>, resourceId: String, team: Boolean): List<FriendRun> {
    val ids = runs.filter { it.contextId.isBlank() && it.localRoomTurn == null && it.resourceId == resourceId && it.team == team }
        .map { it.id }.toMutableSet()
    var added: Boolean
    do {
        added = false
        runs.forEach { run ->
            if (run.agentParentId in ids && ids.add(run.id)) added = true
        }
    } while (added)
    return runs.filter { it.id in ids }
}

internal fun subagentDescendants(run: FriendRun, runs: List<FriendRun>): List<FriendRun> {
    val ids = mutableSetOf(run.id)
    var added: Boolean
    do {
        added = false
        runs.forEach { if (it.agentParentId in ids && ids.add(it.id)) added = true }
    } while (added)
    return runs.filter { it.id != run.id && it.id in ids }
}

internal fun subagentBudgetMembers(run: FriendRun, runs: List<FriendRun>): List<FriendRun> {
    fun attemptRoot(task: FriendRun): String {
        var current = task
        val seen = mutableSetOf<String>()
        while (current.parentId != null && seen.add(current.id))
            current = runs.firstOrNull { it.id == current.parentId } ?: break
        return current.id
    }
    val members = if (run.contextId.isNotBlank()) runs.filter { it.contextId == run.contextId }
        else runs.filter { attemptRoot(it) == attemptRoot(run) }
    val ids = members.map { it.id }.toMutableSet()
    if (run.agentDepth > 0) {
        var added: Boolean
        do {
            added = false
            runs.forEach { if ((it.agentParentId in ids || it.parentId in ids) && ids.add(it.id)) added = true }
        } while (added)
    }
    return runs.filter { it.id in ids }
}

internal fun subagentTaskBudget(run: FriendRun, runs: List<FriendRun>): String {
    val members = subagentBudgetMembers(run, runs)
    return "累计调用 ${members.sumOf { it.modelCallsUsed.toLong() }}/${run.maxModelCalls} · " +
        "累计预留 ${members.sumOf { it.tokensReserved }}/${run.tokenBudget} tok"
}

internal fun subagentCanTarget(run: FriendRun): Boolean = run.contextId.isNotBlank() &&
    !run.agentCancelled && run.state !in setOf("requires_review", "cancelled")

internal fun subagentTaskStateLabel(run: FriendRun): String = when (run.state) {
    "waiting_device" -> "等待设备"
    "waiting_network" -> "等待网络"
    "waiting_budget" -> "等待预算"
    "waiting_resource" -> "等待资源"
    "waiting_external" -> "等待外部"
    else -> friendRunLabel(run.state)
}

@Composable
private fun subagentTaskTone(run: FriendRun): XfTone = when (subagentTaskGroup(run)) {
    SubagentTaskGroup.NeedsYou -> if (run.state == "failed") XfTone.Danger else XfTone.Warning
    SubagentTaskGroup.Running -> XfTone.Accent
    SubagentTaskGroup.Waiting -> XfTone.Neutral
    SubagentTaskGroup.Completed -> if (run.state == "completed") XfTone.Success else XfTone.Neutral
}

@Composable
internal fun SubagentTaskCard(
    run: FriendRun,
    runs: List<FriendRun>,
    onView: () -> Unit,
    onCancel: () -> Unit,
    onContinue: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val parent = runs.firstOrNull { it.id == run.agentParentId }
    val children = subagentDescendants(run, runs)
    XfCard(modifier.fillMaxWidth(), onClick = onView) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(subagentTaskTitle(run), Modifier.weight(1f), style = XfType.bodyStrong, color = xf.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            XfStatusChip(subagentTaskStateLabel(run), tone = subagentTaskTone(run))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (run.contextId.isNotBlank()) "子任务 · 父任务 ${parent?.let(::subagentTaskTitle) ?: run.agentParentId?.take(6).orEmpty()} · 深度 ${run.agentDepth}"
            else "星助理主任务${if (children.isEmpty()) "" else " · ${children.map { it.contextId }.filter { it.isNotBlank() }.distinct().size} 个子任务"}",
            style = XfType.meta, color = xf.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subagentTaskBudget(run, runs).takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = XfType.meta, color = xf.ink3)
        }
        if (run.errorCode.isNotBlank() && run.errorMessage.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(run.errorMessage, style = XfType.small, color = xf.warningText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XfButton("查看", onView, kind = XfButtonKind.Secondary, small = true)
            if (subagentCanTarget(run) && onContinue != null)
                XfButton("继续此任务", onContinue, enabled = enabled, kind = XfButtonKind.Ghost, small = true)
            if (run.active || children.any { it.active })
                XfButton("取消", onCancel, enabled = enabled, kind = XfButtonKind.Danger, small = true)
        }
    }
}

private fun subagentTime(value: Long): String = if (value <= 0) "未设置" else runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
}.getOrDefault("时间不可用")

/** Secondary task panel. Agent transcripts are read here and never copied into the main timeline. */
@Composable
internal fun SubagentTasksPage(s: Store, vm: FriendViewModel, back: () -> Unit) {
    SubagentTasksPage(s, vm, initialRunId = null, back = back)
}

@Composable
internal fun SubagentTasksPage(s: Store, vm: FriendViewModel, initialRunId: String?, back: () -> Unit) {
    var selectedId by remember(initialRunId) { mutableStateOf(initialRunId) }
    var cancellingId by remember { mutableStateOf<String?>(null) }
    val runs = vm.runList
    val selected = selectedId?.let { id -> runs.firstOrNull { it.id == id } }
    val rows = subagentTaskRows(runs)
    BackHandler(enabled = selectedId != null) { selectedId = null }

    fun target(run: FriendRun) {
        vm.replyTarget = null
        vm.targetAgentRunId = run.id
        back()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selectedId == null) {
            item {
                XfPageHeader("任务面板", subtitle = "任务在后台保留独立上下文；点开查看进度、预算与已保存结果。")
            }
            for (group in SubagentTaskGroup.entries) {
                val grouped = rows.filter { subagentTaskGroup(it) == group }
                item("group:$group") { XfSectionHeader("${group.title} · ${grouped.size}") }
                if (grouped.isEmpty()) item("empty:$group") {
                    Text(group.empty, style = XfType.meta, color = xf.ink4, modifier = Modifier.padding(bottom = 4.dp))
                }
                items(grouped, key = { "task:${it.id}" }) { run ->
                    SubagentTaskCard(run, runs, onView = { selectedId = run.id }, onCancel = { cancellingId = run.id },
                        onContinue = { target(run) }, enabled = !vm.busy)
                }
            }
        } else if (selected == null) {
            item { XfEmptyState(XfIcons.Tasks, "任务记录暂不可用", "返回列表后重试。") }
            item { XfButton("返回任务列表", { selectedId = null }, kind = XfButtonKind.Secondary) }
        } else {
            val run = selected
            item {
                XfButton("返回任务列表", { selectedId = null }, kind = XfButtonKind.Ghost, small = true)
                Spacer(Modifier.height(8.dp))
                Text(subagentTaskTitle(run), style = XfType.h2, color = xf.ink)
                Spacer(Modifier.height(8.dp))
                XfStatusChip(subagentTaskStateLabel(run), tone = subagentTaskTone(run))
            }
            item {
                XfCard {
                    XfKeyValueRow("任务编号", run.id.take(12))
                    XfKeyValueRow("执行位置", "此手机")
                    XfKeyValueRow("任务更新时间", subagentTime(run.updated))
                    XfKeyValueRow("检查点", if (run.checkpoint == null) "尚无检查点" else "已保存")
                    if (run.contextId.isNotBlank()) {
                        XfKeyValueRow("独立上下文", run.contextId.take(16))
                        XfKeyValueRow("父任务", run.agentParentId?.take(12).orEmpty().ifBlank { "无" })
                    }
                    val budgetMembers = subagentBudgetMembers(run, runs)
                    XfKeyValueRow("任务深度", run.agentDepth.toString())
                    XfKeyValueRow("本次模型调用 / 预留", "${run.modelCallsUsed} 次 / ${run.tokensReserved} tok")
                    XfKeyValueRow("累计调用 / 上限", "${budgetMembers.sumOf { it.modelCallsUsed.toLong() }} / ${run.maxModelCalls}")
                    XfKeyValueRow("累计预留 / 预算", "${budgetMembers.sumOf { it.tokensReserved }} / ${run.tokenBudget} tok")
                    XfKeyValueRow("截止时间", subagentTime(run.deadlineAt))
                    Text(if (run.contextId.isNotBlank()) "累计包含同一上下文的历次输入与其子任务，共享该代理的预算。预留量不是服务商计费量。"
                        else "累计包含同一主任务的历次请求；子代理另受任务树总预算限制。预留量不是服务商计费量。", style = XfType.meta, color = xf.ink3)
                }
            }
            if (run.errorCode.isNotBlank() && run.errorMessage.isNotBlank()) item {
                XfBanner(run.errorMessage, tone = XfTone.Warning, icon = XfIcons.Bell)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (run.canVerifyCheckpoint) XfButton("核查已执行操作并继续", { vm.verifyRun(run) }, enabled = !vm.busy, kind = XfButtonKind.Secondary)
                    if (run.canResume) XfButton("从已保存检查点继续", { vm.resumeRun(run) }, enabled = !vm.busy, kind = XfButtonKind.Secondary)
                    if (subagentCanTarget(run))
                        XfButton("继续此任务", { target(run) }, enabled = !vm.busy, kind = XfButtonKind.Secondary)
                    if (!run.active && run.state !in setOf("requires_review", "cancelled") && !run.agentCancelled)
                        XfButton("取回输入，重新发起", { vm.retryRun(run); back() }, enabled = !vm.busy, kind = XfButtonKind.Ghost)
                    if (run.active || subagentDescendants(run, runs).any { it.active })
                        XfButton("取消任务", { cancellingId = run.id }, enabled = !vm.busy, kind = XfButtonKind.Danger)
                }
            }
            item {
                XfCard {
                    Text("本次输入", style = XfType.h3, color = xf.ink)
                    Spacer(Modifier.height(8.dp))
                    Text(run.input, style = XfType.body, color = xf.ink2)
                }
            }
            if (run.partial.isNotBlank()) item {
                XfCard {
                    Text(if (run.state == "completed") "结果" else "已保存的输出", style = XfType.h3, color = xf.ink)
                    Spacer(Modifier.height(8.dp))
                    SalesMarkdown(run.partial)
                }
            }
            if (run.contextId.isNotBlank()) {
                item { XfSectionHeader("独立任务上下文") }
                if (run.agentMessages.isEmpty()) item {
                    Text("任务还没有保存对话记录。", style = XfType.small, color = xf.ink3)
                }
                run.agentMessages.toSortedMap().forEach { (key, messages) ->
                    val conversation = StarConversation.fromHistoryKey(key)
                    val name = (if (conversation.team) s.teams.firstOrNull { it.id == conversation.resourceId }?.name
                        else s.experts.firstOrNull { it.id == conversation.resourceId }?.name) ?: "任务上下文"
                    item("context:$key") { Text(name, style = XfType.label, color = xf.ink3) }
                    items(messages.withIndex().toList(), key = { (index, message) -> "message:$key:${starMessageId(message, index)}" }) { (_, message) ->
                        XfCard {
                            Text(if (message.role == "user") "我" else if (message.role == "assistant") name else "任务记录", style = XfType.label, color = xf.ink3)
                            Spacer(Modifier.height(6.dp))
                            if (message.replyToId.isNotBlank()) {
                                Text(starQuotePreview(message.replyToId, message.replyToPreview, messages), style = XfType.meta, color = xf.ink3)
                                Spacer(Modifier.height(6.dp))
                            }
                            SalesMarkdown(message.content)
                        }
                    }
                }
                val attempts = runs.filter { it.contextId == run.contextId }.sortedByDescending { it.created }
                if (attempts.size > 1) {
                    item { XfSectionHeader("同一任务的历次输入 · ${attempts.size}") }
                    items(attempts, key = { "attempt:${it.id}" }) { attempt ->
                        XfCard(onClick = { selectedId = attempt.id }) {
                            Text(attempt.input, style = XfType.small, color = xf.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${subagentTaskStateLabel(attempt)} · ${subagentTime(attempt.created)}", style = XfType.meta, color = xf.ink3)
                        }
                    }
                }
            }
            val children = subagentTaskRows(subagentDescendants(run, runs))
            if (children.isNotEmpty()) {
                item { XfSectionHeader("子任务 · ${children.size}") }
                items(children, key = { "child:${it.id}" }) { child ->
                    SubagentTaskCard(child, runs, { selectedId = child.id }, { cancellingId = child.id }, { target(child) }, !vm.busy)
                }
            }
        }
        vm.error?.let { error -> item("error") { XfBanner(error, tone = XfTone.Danger, icon = XfIcons.Bell) } }
    }
    val cancelling = cancellingId?.let { id -> runs.firstOrNull { it.id == id } }
    if (cancelling != null) XfDialog(
        title = "取消任务？", onDismiss = { cancellingId = null }, confirmLabel = "确认取消", danger = true,
        onConfirm = { vm.cancelRun(cancelling); cancellingId = null },
    ) {
        Text(subagentTaskTitle(cancelling), style = XfType.bodyStrong)
        Text("此任务及其尚未结束的子任务将停止，已经保存的记录和输出会保留。", style = XfType.small)
    }
}
