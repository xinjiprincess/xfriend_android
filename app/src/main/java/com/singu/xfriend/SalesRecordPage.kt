package com.singu.xfriend

import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun SalesMarkdown(text: String) {
    val context = LocalContext.current
    val colors = xf
    val body = XfType.body
    val renderer = remember(context) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).build() }
    val foreground = colors.ink.toArgb()
    val linkColor = colors.accent.toArgb()
    AndroidView(factory = { TextView(it).apply { textSize = body.fontSize.value; setLineSpacing(6f, 1.1f); setTextIsSelectable(true) } },
        modifier = Modifier.fillMaxWidth(), update = { it.setTextColor(foreground); it.setLinkTextColor(linkColor); if (it.tag != text) { renderer.setMarkdown(it, text); it.tag = text } })
}

internal fun playbackTime(milliseconds: Long): String = "%02d:%02d".format(milliseconds / 60000, milliseconds / 1000 % 60)

/**
 * 录音播放条：紧凑版用于毛玻璃底栏上方，完整版用于复盘/客户档案。
 * 只改视觉，Media3 播放逻辑仍全部走 [SalesPlayback] / SalesViewModel 现有 API。
 */
@Composable internal fun SalesPlayer(vm: SalesViewModel, record: Communication? = null, compact: Boolean = false) {
    val colors = xf
    val p = vm.playback
    val selected = record == null || p.recordId == record.id
    val title = record?.title ?: p.title
    val playing = selected && p.playing
    XfCard(padding = if (compact) 8.dp else XfDimens.cardPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.accentSolid)
                    .clickable { if (!selected && record != null) vm.play(record) else p.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) XfIcons.Pause else XfIcons.Play, if (playing) "暂停" else "播放", Modifier.size(18.dp), tint = colors.surface)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = XfType.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (selected) "${playbackTime(p.position)} / ${playbackTime(p.duration)}" else "点击播放录音", style = XfType.meta, color = colors.ink3)
            }
            if (compact) {
                XfIconButton(XfIcons.X, "关闭播放器", vm::stopPlayback, size = 32.dp)
            } else {
                XfButton("${p.speed}×", { p.rate(when (p.speed) { .75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> .75f }) }, enabled = selected, kind = XfButtonKind.Ghost, small = true)
            }
        }
        if (!compact) {
            if (selected && p.peaks.isNotEmpty()) {
                Spacer(Modifier.height(XfDimens.rowGap))
                Canvas(Modifier.fillMaxWidth().height(42.dp)) {
                    val dx = size.width / p.peaks.size
                    p.peaks.forEachIndexed { i, peak ->
                        val played = i.toFloat() / p.peaks.size <= p.position.toFloat() / p.duration.coerceAtLeast(1)
                        val h = peak * size.height
                        drawLine(colors.accent.copy(alpha = if (played) 1f else .22f), Offset(i * dx, (size.height - h) / 2), Offset(i * dx, (size.height + h) / 2), dx * .5f)
                    }
                }
            }
            Slider(value = if (selected) p.position.toFloat() else 0f, onValueChange = { p.seek(it.toLong()) }, valueRange = 0f..p.duration.coerceAtLeast(1).toFloat(), enabled = selected && p.duration > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                XfButton("−15 秒", { p.seek(p.position - 15000) }, enabled = selected, kind = XfButtonKind.Ghost, small = true)
                XfButton("+15 秒", { p.seek(p.position + 15000) }, enabled = selected, kind = XfButtonKind.Ghost, small = true)
            }
            if (selected && p.error.isNotBlank()) Text(p.error, style = XfType.small, color = colors.dangerText)
        }
    }
}

@Composable internal fun SalesRecordPage(original: Communication, vm: SalesViewModel, friend: FriendViewModel, onFollowUp: (Communication) -> Unit, back: () -> Unit) {
    val colors = xf
    val r = vm.data?.records?.find { it.id == original.id } ?: original
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(r.id) { mutableIntStateOf(if (r.report.isNotBlank()) 0 else 1) }
    var title by rememberSaveable(r.id) { mutableStateOf(r.title) }
    var customer by rememberSaveable(r.id) { mutableStateOf(r.customerId) }
    var editing by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    var portrait by remember { mutableStateOf<CustomerInsight?>(null) }
    var actionDraft by remember { mutableStateOf<FollowUp?>(null) }
    val analysis = remember(r.analysisJson) { CustomerAnalysis.read(r.analysisJson) }
    var remove by remember { mutableStateOf(false) }
    var analyzeConsent by remember { mutableStateOf(false) }
    var showEvidence by remember { mutableStateOf<JSONObject?>(null) }
    var reviewCustomer by remember { mutableStateOf(false) }
    val task = vm.tasks.lastOrNull { it.recordId == r.id && it.active }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch { runCatching { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write("# ${r.title}\n\n${r.report}\n\n## 沟通原文\n${r.text}") }
        } }.onFailure { vm.error = "报告导出失败，请重试" } }
    }
    BackHandler { vm.saveRecordMetadata(r.id, title, customer); back() }
    LaunchedEffect(title, customer) { delay(800); if ((title != r.title || customer != r.customerId) && title.isNotBlank()) vm.saveRecordMetadata(r.id, title, customer) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(XfDimens.pagePadding, XfDimens.rowGap, XfDimens.pagePadding, 110.dp), verticalArrangement = Arrangement.spacedBy(XfDimens.cardPadding)) {
        item {
            Column {
                SalesBackButton("返回") { vm.saveRecordMetadata(r.id, title, customer); back() }
                XfPageHeader(title = "沟通复盘", subtitle = "摘要、原文、行动与附件")
            }
        }
        item {
            XfCard {
                Input("沟通标题", title, { title = it.take(100) })
                Spacer(Modifier.height(XfDimens.rowGap))
                ChoiceRow("关联客户", listOf("" to "待归档") + vm.data?.customers.orEmpty().map { it.id to it.name }, customer) { customer = it }
            }
        }
        if (r.kind == "audio" && r.file.isNotBlank()) item { SalesPlayer(vm, r) }
        if (r.kind == "image") item {
            XfButton("核对截图并保存客户", { reviewCustomer = true }, enabled = task == null && !vm.busy, modifier = Modifier.fillMaxWidth(), kind = XfButtonKind.Secondary, icon = XfIcons.UserPlus)
        }
        if (task != null) item {
            val percent = salesEstimatedProgress(task, r)
            XfCard(padding = XfDimens.cardPaddingCompact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfStatusChip("正在处理", tone = XfTone.Accent, icon = XfIcons.Refresh)
                    Spacer(Modifier.width(XfDimens.rowGap))
                    Text(if (percent != null) "预计进度 · $percent%" else "进度自动保存", style = XfType.meta, color = colors.ink3)
                }
                Spacer(Modifier.height(XfDimens.rowGap))
                XfProgressBar(percent?.div(100f))
                Spacer(Modifier.height(XfDimens.rowGap))
                Text("进度会自动保存，可以继续使用其他页面。", style = XfType.meta, color = colors.ink3)
            }
        }
        item {
            XfSegmented(
                options = listOf("摘要", "原文", "行动", "附件"),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }
        when (tab) {
            0 -> {
                if (r.report.isBlank()) item { XfCard { XfEmptyState(XfIcons.Sparkles, "让沟通变成下一步", "选择专家或团队，为这次沟通生成复盘。") } }
                else {
                    item {
                        XfCard {
                            XfStatusChip(if (task != null) "正在生成 · 待核对" else "AI 分析 · 待核对", tone = XfTone.Warning)
                            Spacer(Modifier.height(XfDimens.itemGap))
                            SalesMarkdown(r.report)
                            Spacer(Modifier.height(XfDimens.itemGap))
                            Text("以下结论和建议尚未写入客户档案，请结合原文核对。", style = XfType.meta, color = colors.ink3)
                        }
                    }
                    val evidence = runCatching { JSONObject(r.analysisJson).optJSONArray("evidence")?.objects().orEmpty() }.getOrDefault(emptyList())
                    if (evidence.isNotEmpty()) item { XfSectionHeader("原文证据") }
                    itemsIndexed(evidence) { _, e ->
                        XfCard(padding = XfDimens.cardPaddingCompact, onClick = { showEvidence = e }) {
                            Row(verticalAlignment = Alignment.Top) {
                                XfIconTile(XfIcons.File, size = 32.dp, tone = XfTone.Neutral)
                                Spacer(Modifier.width(XfDimens.rowGap))
                                Column(Modifier.weight(1f)) {
                                    Text(e.optString("id", "沟通证据"), style = XfType.label, color = colors.accentInk)
                                    Text(e.optString("quote"), style = XfType.small, color = colors.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4)
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                            XfButton("导出报告", { export.launch("Xsalers-复盘报告.md") }, kind = XfButtonKind.Ghost, small = true)
                            XfButton("核对下一步行动", { tab = 2 }, kind = XfButtonKind.Secondary, small = true)
                        }
                    }
                }
            }
            1 -> {
                if (r.text.isBlank()) item { XfCard { XfEmptyState(XfIcons.File, "原文准备中", "开始复盘后，转写与识别内容会自动保存在这里。") } }
                val blocks = r.text.chunked(4000).ifEmpty { listOf("") }
                itemsIndexed(blocks) { index, value ->
                    XfCard {
                        SalesMarkdown(value)
                        XfButton(if (value.isBlank()) "填写沟通内容" else "校正内容", { editing = Triple(index, value, r.text) }, kind = XfButtonKind.Ghost, small = true)
                    }
                }
            }
            2 -> {
                item {
                    Column {
                        XfSectionHeader("建议下一步", subtitle = "AI 建议可修改。核对客户、内容与时间后，再确认保存。")
                        if (customer.isBlank()) Text("请先在上方关联客户，再保存画像或跟进。", style = XfType.small, color = colors.accentInk)
                    }
                }
                if (analysis != null && analysis.profile != CustomerInsight()) item {
                    XfCard(padding = 0.dp, onClick = {
                        vm.data?.customers?.find { it.id == customer }?.let { portrait = it.insight.withCandidate(analysis.profile) }
                    }) {
                        XfListRow(
                            title = "核对客户画像",
                            subtitle = analysis.profile.summary.ifBlank { analysis.profile.needs }.ifBlank { "检查需求、偏好、预算和决策角色" },
                            modifier = Modifier.padding(horizontal = XfDimens.cardPaddingCompact),
                            leading = { XfIconTile(XfIcons.User, size = 36.dp) },
                            trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                            minHeight = 64.dp,
                        )
                    }
                }
                itemsIndexed(analysis?.actions.orEmpty()) { _, action ->
                    XfCard {
                        Text(action.title, style = XfType.bodyStrong, color = colors.ink)
                        Text("建议时间：${action.dueDate.ifBlank { "待确定" }} ${action.dueTime}", style = XfType.meta, color = colors.ink3)
                        XfButton("编辑并确认跟进", { actionDraft = FollowUp(customerId = customer, title = action.title, dueDate = action.dueDate, dueTime = action.dueTime, priority = action.priority, recordId = r.id) }, enabled = customer.isNotBlank() && !vm.busy, kind = XfButtonKind.Secondary, small = true)
                    }
                }
                item { XfButton("手动添加跟进", { onFollowUp(r.copy(customerId = customer)) }, enabled = customer.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth(), kind = XfButtonKind.Secondary) }
            }
            3 -> {
                val assets = (listOf(r.file) + r.attachments).filter { it.isNotBlank() }.distinct()
                if (assets.isEmpty()) item { XfCard { XfEmptyState(XfIcons.Folder, "没有附件", "本次沟通使用文字记录。") } }
                itemsIndexed(assets) { index, file ->
                    if (file.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                        Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                            Text("原始材料 ${index + 1}", style = XfType.label, color = colors.ink2)
                            SalesImage(vm.mediaPath(file))
                        }
                    } else {
                        XfCard(padding = XfDimens.cardPaddingCompact) { Text(file, style = XfType.small, color = colors.ink2) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
                ExecutionChoice(vm, friend.store!!, task == null)
                XfButton(if (r.report.isBlank()) "开始复盘" else "重新复盘", { analyzeConsent = true }, enabled = task == null && (r.file.isNotBlank() || r.text.isNotBlank()) && !vm.busy, modifier = Modifier.fillMaxWidth())
                XfButton("删除此沟通", { remove = true }, enabled = task == null, kind = XfButtonKind.Danger, modifier = Modifier.fillMaxWidth(), small = true)
            }
        }
    }
    if (reviewCustomer) ScreenshotCustomerReview(r, vm) {
        customer = vm.data?.records?.find { it.id == r.id }?.customerId ?: customer
        reviewCustomer = false
    }
    if (analyzeConsent) XfDialog(
        title = "开始这次复盘？",
        onDismiss = { analyzeConsent = false },
        confirmLabel = "确认开始复盘",
        onConfirm = { analyzeConsent = false; vm.analyze(r.copy(title = title, customerId = customer), friend.store!!); tab = 0 },
    ) {
        Text("将本次沟通和已选客户资料发送到所选模型服务。结果以建议形式保存，确认后再更新客户档案。", style = XfType.small, color = xf.ink2)
    }
    editing?.let { (index, initial, expected) ->
        var value by remember(index) { mutableStateOf(initial) }
        XfDialog(
            title = "校正沟通原文",
            onDismiss = { editing = null },
            confirmLabel = "确认保存原文",
            onConfirm = {
                val parts = expected.chunked(4000).ifEmpty { listOf("") }.toMutableList(); parts[index] = value
                vm.correctRecordText(r.id, expected, parts.joinToString("")); editing = null
            },
        ) {
            XfTextField(value = value, onValueChange = { value = it }, placeholder = "填写沟通内容", singleLine = false, minLines = 6)
        }
    }
    showEvidence?.let { e ->
        XfDialog(
            title = "原始证据",
            onDismiss = { showEvidence = null },
            confirmLabel = "关闭",
            onConfirm = { showEvidence = null },
            dismissLabel = null,
        ) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
                Text(e.optString("quote"), style = XfType.body, color = xf.ink)
                if (r.kind == "audio" && e.has("startSeconds")) {
                    XfButton("播放对应录音", { vm.play(r); vm.playback.seek((e.optDouble("startSeconds", 0.0).coerceAtLeast(0.0) * 1000).toLong()); showEvidence = null }, kind = XfButtonKind.Secondary, small = true)
                }
                XfButton("核对沟通原文", { tab = 1; showEvidence = null }, kind = XfButtonKind.Ghost, small = true)
                e.optString("asset").takeIf { it.matches(Regex("[a-zA-Z0-9-]+\\.jpg")) }?.let { SalesImage(vm.mediaPath(it)) }
            }
        }
    }
    portrait?.let { candidate -> vm.data?.customers?.find { it.id == customer }?.let { c -> CustomerInsightEditor(c, candidate, vm) { portrait = null } } }
    actionDraft?.let { draft -> SalesFollowUpEditor(draft, vm) { actionDraft = null } }
    if (remove) XfDialog(
        title = "删除这次沟通？",
        onDismiss = { remove = false },
        confirmLabel = "确认删除沟通",
        onConfirm = { vm.removeRecord(r.id); back() },
        dismissLabel = "保留",
        danger = true,
    ) {
        Text("同时删除本机附件和报告，客户资料会保留。", style = XfType.small, color = xf.ink2)
    }
}

/** 证据行的小图标：XfIcons 没有引用图标时退回线性文件图标。 */

@Composable internal fun SalesImage(path: String) {
    val colors = xf
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, options)
        var sample = 1; while (options.outWidth.toLong() * options.outHeight / sample / sample > 2_000_000) sample *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull() } }
    var expanded by remember(path) { mutableStateOf(false) }
    bitmap?.let { preview ->
        Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
            Image(preview.asImageBitmap(), "沟通截图预览", Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(XfDimens.cardRadius)))
            XfButton("展开查看原始材料", { expanded = true }, modifier = Modifier.fillMaxWidth(), kind = XfButtonKind.Secondary, small = true)
        }
        if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            XfSurface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp), color = colors.canvas, border = false) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(XfDimens.pagePadding)) {
                    XfTopBar(title = "原始材料", onBack = { expanded = false })
                    Text("可上下滚动查看长图。预览经过缩放，原始文件仍保留。", style = XfType.meta, color = colors.ink3)
                    Spacer(Modifier.height(XfDimens.rowGap))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Image(preview.asImageBitmap(), "完整沟通截图", Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height.coerceAtLeast(1)))
                    }
                }
            }
        }
    } ?: Text("图片暂不可用，请检查本机原始材料。", style = XfType.small, color = colors.ink3)
}
