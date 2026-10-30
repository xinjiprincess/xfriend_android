package com.singu.xfriend

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only media/import jobs expose their persisted weighted progress as an estimate. */
internal fun salesEstimatedProgress(task: SalesTask, record: Communication? = null): Int? {
    val audio = record?.kind == "audio" || runCatching {
        JSONObject(task.payload).optJSONObject("item")?.optString("kind") == "audio"
    }.getOrDefault(false)
    if (task.operation != "import" && !(task.operation == "analyze" && audio)) return null
    return if (task.status == "done") 100 else task.progress.coerceIn(0, 99)
}

/** 任务进度：仅在引擎/媒体导入提供真实估算时显示百分比；其余运行中用不确定进度条。 */
@Composable internal fun SalesTaskProgress(task: SalesTask, record: Communication? = null) {
    val colors = xf
    val percent = salesEstimatedProgress(task, record)
    if (percent != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("预计进度 · $percent%", style = XfType.meta, color = colors.ink2)
            XfProgressBar(progress = percent / 100f)
        }
    } else if (task.active) {
        XfProgressBar(progress = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SalesTaskScreen(vm: SalesViewModel, onRecord: (Communication) -> Unit = {}, back: () -> Unit) {
    val colors = xf
    var filter by rememberSaveable { mutableStateOf("全部") }
    var cancelId by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    val filters = listOf("全部", "进行中", "待处理", "已完成")
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(XfDimens.pagePadding, 8.dp, XfDimens.pagePadding, 100.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            Column {
                SalesBackButton("返回工作空间", back)
                Spacer(Modifier.height(4.dp))
                XfPageHeader(title = "任务中心", subtitle = "查看处理状态、核对结果，或从已保存进度继续。")
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT >= 33) {
                    XfButton(
                        text = "允许进度通知",
                        onClick = { notification.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        kind = XfButtonKind.Secondary,
                        icon = XfIcons.Bell,
                        small = true,
                        modifier = Modifier.heightIn(min = XfDimens.touch),
                    )
                }
                XfButton(
                    text = "清理已完成任务",
                    onClick = { clear = true },
                    kind = XfButtonKind.Danger,
                    icon = XfIcons.X,
                    small = true,
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
        }
        item {
            XfSegmented(
                options = filters,
                selectedIndex = filters.indexOf(filter).coerceAtLeast(0),
                onSelect = { filter = filters[it] },
            )
        }
        val visible = vm.tasks.filter {
            when (filter) {
                "进行中" -> it.active
                "已完成" -> it.status == "done"
                "待处理" -> !it.active && it.status != "done"
                else -> true
            }
        }
        if (visible.isEmpty()) {
            item {
                XfEmptyState(
                    icon = XfIcons.Tasks,
                    title = "没有后台任务",
                    body = "导入文件或确认分析后，会在这里显示进度和结果。",
                )
            }
        }
        items(visible.sortedByDescending { it.created }, key = { it.id }) { task ->
            SalesTaskCard(
                task = task,
                record = vm.data?.records?.find { it.id == task.recordId },
                onRecord = onRecord,
                onRetry = { vm.retryTask(task.id) },
                onStop = { cancelId = task.id },
            )
        }
    }
    cancelId?.let { id ->
        XfDialog(
            title = "停止这个任务？",
            onDismiss = { cancelId = null },
            confirmLabel = "确认停止处理",
            onConfirm = { vm.cancelTask(id); cancelId = null },
            dismissLabel = "继续处理",
            danger = true,
        ) {
            Text(
                "已保存的材料与进度会保留。正在执行但结果未知的操作，继续前需要核对。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
    if (clear) {
        XfDialog(
            title = "清理已完成任务？",
            onDismiss = { clear = false },
            confirmLabel = "确认清理任务",
            onConfirm = { vm.clearFinishedTasks(); clear = false },
            dismissLabel = "取消",
            danger = true,
        ) {
            Text(
                "仅清理任务列表中的已完成项，沟通记录和报告仍保留。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SalesTaskCard(
    task: SalesTask,
    record: Communication?,
    onRecord: (Communication) -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = xf
    var expanded by rememberSaveable(task.id, task.detail) { mutableStateOf(false) }
    XfCard {
        Row(verticalAlignment = Alignment.Top) {
            XfIconTile(icon = salesTaskIcon(task.status), size = 40.dp, tone = salesTaskTone(task.status))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = XfType.h3,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XfStatusChip(label = salesTaskLabel(task.status), tone = salesTaskTone(task.status))
                    Text(salesTaskTime(task.created), style = XfType.meta, color = colors.ink3)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(salesTaskDescription(task), style = XfType.small, color = colors.ink2)
        Spacer(Modifier.height(10.dp))
        SalesTaskProgress(task, record)
        if (task.status == "failed") {
            Spacer(Modifier.height(4.dp))
            XfButton(
                text = if (expanded) "收起错误详情" else "错误详情",
                onClick = { expanded = !expanded },
                kind = XfButtonKind.Ghost,
                icon = if (expanded) XfIcons.ChevDown else XfIcons.ChevRight,
                small = true,
                modifier = Modifier.heightIn(min = XfDimens.touch),
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                XfSurface(
                    shape = RoundedCornerShape(XfDimens.smallRadius),
                    color = colors.surface2,
                ) {
                    // SalesTask persists the worker exception message in detail; no separate
                    // error/code field exists. Preserve any diagnostic code in that message.
                    SelectionContainer {
                        Text(
                            task.detail.ifBlank { "未记录具体原因，请检查模型配置和网络后重试。" },
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = XfType.mono,
                            color = colors.ink2,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Hr()
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (record != null) {
                XfButton(
                    text = if (task.status == "done") "查看结果" else "查看沟通",
                    onClick = { onRecord(record) },
                    kind = XfButtonKind.Secondary,
                    small = true,
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
            if (task.status in listOf("failed", "queued", "cancelled")) {
                XfButton(
                    text = "从已保存进度继续",
                    onClick = onRetry,
                    kind = XfButtonKind.Primary,
                    icon = XfIcons.Refresh,
                    small = true,
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
            if (task.active) {
                XfButton(
                    text = "停止处理",
                    onClick = onStop,
                    kind = XfButtonKind.Danger,
                    icon = XfIcons.Stop,
                    small = true,
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
        }
    }
}

private fun salesTaskLabel(status: String): String = when (status) {
    "queued" -> "排队中"
    "running" -> "处理中"
    "done" -> "已完成"
    "failed" -> "需要处理"
    else -> "已暂停"
}

private fun salesTaskTone(status: String): XfTone = when (status) {
    "done" -> XfTone.Success
    "failed" -> XfTone.Warning
    "running" -> XfTone.Accent
    else -> XfTone.Neutral
}

private fun salesTaskIcon(status: String): ImageVector = when (status) {
    "queued" -> XfIcons.Clock
    "running" -> XfIcons.Refresh
    "done" -> XfIcons.CheckCircle
    "failed" -> XfIcons.X
    else -> XfIcons.Pause
}

private fun salesTaskDescription(task: SalesTask): String {
    if (task.active) {
        return if (task.status == "queued") "等待开始处理"
        else if (task.operation == "analyze") "正在整理沟通与分析建议"
        else "正在导入原始材料"
    }
    return when (task.status) {
        "done" -> if (task.operation == "analyze") "分析已完成，请核对建议后保存。" else "材料已导入，尚未确认写入客户资料。"
        "failed" -> "这次复盘未能完成，已保留进度。请稍后重试。"
        else -> "进度已保存，可以继续复盘。"
    }
}

private fun salesTaskTime(created: Long): String =
    Instant.ofEpochMilli(created).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
