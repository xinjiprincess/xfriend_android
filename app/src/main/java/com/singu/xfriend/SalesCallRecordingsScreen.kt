package com.singu.xfriend

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 候选录音在多选里的稳定 key；系统录音用 uri，应用内录音用文件名兜底。 */
private fun callRecordingSelectKey(record: CallRecording): String =
    CallRecordings.scanKey(record).ifBlank { record.uri + "|" + record.name }

/** 候选录音的时间 · 时长 · 大小 · 来源文件夹，全部复用 [CallRecordings] 的格式化函数。 */
private fun callRecordingMeta(record: CallRecording): String = buildString {
    append(CallRecordings.dateText(record.capturedAt))
    append(" · ").append(CallRecordings.durationText(record.durationMs))
    append(" · ").append(CallRecordings.sizeText(record.sizeBytes))
    if (record.folder.isNotBlank()) append(" · ").append(record.folder)
}

/**
 * 通话录音接管页。
 *
 * Android 10 起第三方应用无法在通话中录音，所以这里接管的是系统/录音机应用已经保存的通话录音：
 * 复制到应用私有目录后复用现有的语音转写 → 复盘分析流程。文字保存成功后，用户可以删除源音频，
 * 只保留文字与复盘报告；接管、复制与解码在本机完成，转写按模型配置调用 ASR。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SalesCallRecordingsScreen(vm: SalesViewModel, friend: FriendViewModel, onBack: () -> Unit) {
    val colors = xf
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteId by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf<Communication?>(null) }
    val settings = vm.callSettings()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 无论授权成功还是被拒绝都重新扫描：授权后列出录音，拒绝时由 ViewModel 更新受限状态与提示。
        vm.scanCallRecordings(true)
    }
    val deletion = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = pendingDeleteId
        pendingDeleteId = ""
        if (result.resultCode == Activity.RESULT_OK && id.isNotBlank()) vm.confirmSourceDeleted(id)
    }
    fun deleteSource(record: Communication) {
        // API 30+ 由系统弹出删除确认，返回的 IntentSender 交给 ActivityResult API；
        // API 30 以下与本地录音由 ViewModel 直接删除，调用方先做二次确认。
        val sender = vm.requestSourceDeletion(context, record)
        if (sender != null) {
            pendingDeleteId = record.id
            deletion.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    LaunchedEffect(Unit) {
        // 应用启动时的自动扫描可能在页面打开前就已完成；这里只补一次空列表时的扫描。
        if (!vm.callScanning && vm.callRecordings.isEmpty() && vm.hasAudioPermission() && vm.callSettings().autoScan) {
            vm.scanCallRecordings()
        }
    }
    // 接管或忽略成功后候选会从列表移除，这里同步清理已经失效的选中项。
    LaunchedEffect(vm.callRecordings) {
        if (selected.isEmpty()) return@LaunchedEffect
        val keys = vm.callRecordings.map { callRecordingSelectKey(it) }.toSet()
        if (selected.any { it !in keys }) selected = selected.intersect(keys)
    }

    val selectedRecordings = vm.callRecordings.filter { callRecordingSelectKey(it) in selected }
    val allSelected = vm.callRecordings.isNotEmpty() && vm.callRecordings.all { callRecordingSelectKey(it) in selected }
    val pendingDeletions = vm.pendingSourceDeletions()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        XfTopBar(title = "通话录音接管", onBack = onBack)
        if (vm.notice.isNotBlank()) {
            XfBanner(
                text = vm.notice,
                tone = XfTone.Success,
                icon = XfIcons.CheckCircle,
                action = "关闭",
                onAction = { vm.dismissNotice() },
            )
        }
        vm.error?.let { message ->
            XfBanner(
                text = message,
                tone = XfTone.Danger,
                icon = XfIcons.Bell,
                action = "关闭",
                onAction = { vm.dismissNotice() },
            )
        }

        XfCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XfIconTile(XfIcons.Mic, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text("接管已保存的通话录音", style = XfType.h3, color = colors.ink)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Android 10 起第三方应用无法在通话中直接录音。这里读取系统或录音机应用已经保存的通话录音，导入应用私有目录后复用现有转写流程。",
                style = XfType.small,
                color = colors.ink2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "转写成功后可以删除源音频，文字与复盘报告仍会保留。接管、复制与解码都在本机完成；点开始转写后，音频会按当前模型配置发送给对应的 ASR 服务（默认 MiMo 云端），不会写入本应用的服务器。",
                style = XfType.small,
                color = colors.ink2,
            )
        }

        if (!vm.hasAudioPermission() || vm.callScanBlocked) {
            XfBanner(
                text = "需要允许读取音频，才能发现系统通话录音",
                tone = XfTone.Warning,
                icon = XfIcons.Shield,
                action = "授权",
                onAction = { permission.launch(CallRecordings.permission()) },
            )
        }

        XfSectionHeader(
            title = "可接管录音",
            subtitle = vm.callScanNote.takeIf { it.isNotBlank() },
            action = "重新扫描",
            onAction = { vm.scanCallRecordings(true) },
        )
        if (vm.callScanning) XfProgressBar(progress = null)
        if (vm.callRecordings.isEmpty()) {
            XfEmptyState(
                icon = XfIcons.Mic,
                title = "暂无可接管的通话录音",
                body = if (!vm.hasAudioPermission()) {
                    "允许读取音频后，这里会列出系统里已保存的通话录音。"
                } else {
                    "通话录音通常保存在「录音」相关文件夹；可稍后重新扫描。"
                },
            )
        } else {
            XfCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = XfDimens.cardPaddingCompact)) {
                    XfListRow(
                        title = "全选可接管录音",
                        meta = "已选 ${selected.size} / ${vm.callRecordings.size} 条",
                        leading = {
                            XfIconTile(
                                XfIcons.CheckCircle,
                                size = 34.dp,
                                tone = if (allSelected) XfTone.Accent else XfTone.Neutral,
                            )
                        },
                        trailing = {
                            XfLink(
                                text = if (allSelected) "取消全选" else "全选",
                                onClick = {
                                    selected = if (allSelected) emptySet()
                                    else vm.callRecordings.map { callRecordingSelectKey(it) }.toSet()
                                },
                            )
                        },
                        onClick = {
                            selected = if (allSelected) emptySet()
                            else vm.callRecordings.map { callRecordingSelectKey(it) }.toSet()
                        },
                        showDivider = true,
                    )
                    vm.callRecordings.forEachIndexed { index, record ->
                        val key = callRecordingSelectKey(record)
                        val isSelected = key in selected
                        val customer = vm.data?.customers?.find { it.id == record.customerId }
                        XfListRow(
                            title = CallRecordings.titleOf(record.name),
                            meta = callRecordingMeta(record),
                            leading = {
                                XfIconTile(
                                    XfIcons.Mic,
                                    size = 34.dp,
                                    tone = if (isSelected) XfTone.Accent else XfTone.Neutral,
                                )
                            },
                            trailing = {
                                if (isSelected) {
                                    Icon(
                                        XfIcons.Check,
                                        contentDescription = "已选择",
                                        modifier = Modifier.size(15.dp),
                                        tint = colors.accent,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                XfStatusChip(
                                    label = customer?.name ?: "未匹配客户",
                                    tone = if (customer != null) XfTone.Accent else XfTone.Neutral,
                                )
                            },
                            onClick = {
                                selected = if (isSelected) selected - key else selected + key
                            },
                            showDivider = index != vm.callRecordings.lastIndex,
                        )
                    }
                }
            }
        }

        if (selectedRecordings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap),
                    verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap),
                ) {
                    XfButton(
                        text = "接管并转写 (${selectedRecordings.size})",
                        onClick = { vm.takeoverCallRecordings(selectedRecordings, friend.store) },
                        icon = XfIcons.Mic,
                        enabled = !vm.busy,
                    )
                    XfButton(
                        text = "忽略所选",
                        onClick = { vm.skipCallRecordings(selectedRecordings) },
                        kind = XfButtonKind.Ghost,
                        enabled = !vm.busy,
                    )
                }
                Text(
                    "接管后会复制到应用私有目录并自动转写；配置好模型才会生成文字与复盘。",
                    style = XfType.meta,
                    color = colors.ink3,
                )
            }
        }

        XfSectionHeader(title = "设置", subtitle = "接管与清理偏好")
        XfCard {
            XfListRow(
                title = "打开 App 时自动扫描",
                subtitle = "进入工作台后自动检查系统里新出现的通话录音",
                trailing = {
                    XfSwitch(
                        checked = settings.autoScan,
                        onCheckedChange = { vm.saveCallSettings(it, settings.deleteAfterTranscribe) },
                    )
                },
            )
            Hr()
            XfListRow(
                title = "转写完成后提示删除源录音",
                subtitle = "文字保存成功后在「待清理源录音」中提示",
                trailing = {
                    XfSwitch(
                        checked = settings.deleteAfterTranscribe,
                        onCheckedChange = { vm.saveCallSettings(settings.autoScan, it) },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            XfBanner(
                tone = XfTone.Info,
                text = "删除的是系统录音文件与应用内解码副本，删除后无法恢复；文字与复盘报告会保留。",
                icon = XfIcons.Shield,
            )
        }

        if (pendingDeletions.isNotEmpty()) {
            XfSectionHeader(title = "待清理源录音", subtitle = "文字已保存，源录音可删除")
            XfCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = XfDimens.cardPaddingCompact)) {
                    pendingDeletions.forEachIndexed { index, record ->
                        XfListRow(
                            title = record.title,
                            meta = record.sourceName.ifBlank { "源录音已转写" },
                            leading = { XfIconTile(XfIcons.File, size = 34.dp, tone = XfTone.Neutral) },
                            trailing = {
                                XfButton(
                                    text = "删除源录音",
                                    onClick = {
                                        if (record.sourceUri.isBlank()) deleteConfirm = record else deleteSource(record)
                                    },
                                    kind = XfButtonKind.Ghost,
                                    small = true,
                                    enabled = !vm.busy,
                                )
                            },
                            showDivider = index != pendingDeletions.lastIndex,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    deleteConfirm?.let { record ->
        XfDialog(
            title = "删除源录音？",
            onDismiss = { deleteConfirm = null },
            confirmLabel = "确认删除",
            onConfirm = {
                deleteConfirm = null
                deleteSource(record)
            },
            dismissLabel = "保留源录音",
            danger = true,
        ) {
            Text(
                "将删除系统里的「${record.sourceName.ifBlank { record.title }}」，删除后无法恢复。文字与复盘报告会保留。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
}
