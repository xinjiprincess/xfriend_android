package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.util.UUID

/**
 * 截图识别结果的核对界面：只读建议 + 用户确认后写入。
 * 未识别的字段留空并保留已有信息；重复电话必须改选已有客户。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenshotCustomerReview(record: Communication, vm: SalesViewModel, onClose: () -> Unit) {
    val colors = xf
    val source = remember(record.id) { record }
    val assets = remember(source) {
        if (source.kind != "image") emptyList() else (listOf(source.file) + source.attachments)
            .filter { it.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif") }
            .distinct()
    }
    val candidate = remember(source) {
        runCatching {
            val analysis = JSONObject(source.analysisJson)
            val evidence = analysis.optJSONArray("evidence")?.objects().orEmpty().filter {
                it.optString("asset") in assets && it.optString("quote").isNotBlank() &&
                    (source.text.contains(it.optString("quote")) || source.rawTranscript.contains(it.optString("quote")))
            }
            validatedScreenshotContact(analysis.optJSONObject("contactCandidate"), evidence)
        }.getOrDefault(JSONObject())
    }
    var name by rememberSaveable(source.id) { mutableStateOf(candidate.optJSONObject("name")?.optString("value").orEmpty()) }
    var company by rememberSaveable(source.id) { mutableStateOf(candidate.optJSONObject("company")?.optString("value").orEmpty()) }
    var phone by rememberSaveable(source.id) { mutableStateOf(candidate.optJSONObject("phone")?.optString("value").orEmpty()) }
    var existingMode by rememberSaveable(source.id) { mutableStateOf(source.customerId.isNotBlank()) }
    var selectedId by rememberSaveable(source.id) { mutableStateOf(source.customerId) }
    var search by rememberSaveable(source.id) { mutableStateOf("") }
    val newId = rememberSaveable(source.id) { UUID.randomUUID().toString() }
    var preview by remember { mutableStateOf<Customer?>(null) }
    var expected by remember { mutableStateOf<Customer?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }
    val customers = vm.data?.customers.orEmpty()
    val selected = customers.find { it.id == selectedId }
    val matches = customers.filter {
        (normalizedPhone(phone).isNotBlank() && normalizedPhone(it.phone) == normalizedPhone(phone)) ||
            (name.isNotBlank() && it.name.equals(name.trim(), true)) ||
            (company.isNotBlank() && it.company.equals(company.trim(), true))
    }
    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !saving)) {
        XfSurface(modifier = Modifier.fillMaxSize(), shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp), color = colors.canvas, border = false) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = XfDimens.pagePadding)) {
                XfPageHeader(
                    title = "截图导入",
                    subtitle = "只预填原文明确标注的客户字段",
                    actions = { XfButton(if (saved) "完成" else "关闭", onClose, enabled = !saving, kind = XfButtonKind.Ghost, small = true) },
                )
                Spacer(Modifier.height(XfDimens.rowGap))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(XfDimens.cardPadding)) {
                    if (saved) {
                        XfCard {
                            XfStatusChip("客户资料已保存", tone = XfTone.Success, icon = XfIcons.CheckCircle)
                            Spacer(Modifier.height(XfDimens.itemGap))
                            Text("原沟通记录与截图已关联到 ${preview?.name.orEmpty()}，可在客户档案查看。", style = XfType.body, color = colors.ink)
                        }
                    } else {
                        Text(if (candidate.length() == 0) "请核对原图并补充客户资料" else "识别完成，请核对", style = XfType.h2, color = colors.ink)
                        Text("只预填原文明确标注的客户字段。未识别字段留空，不会覆盖已有信息。", style = XfType.small, color = colors.ink2)
                    }
                    XfButton(
                        if (showSources) "收起原始截图" else "查看原始截图（${assets.size}）",
                        { showSources = !showSources },
                        modifier = Modifier.fillMaxWidth(),
                        kind = XfButtonKind.Secondary,
                        small = true,
                    )
                    if (showSources) {
                        XfCard {
                            assets.forEach { SalesImage(vm.mediaPath(it)) }
                        }
                    }
                    if (!saved && preview == null) {
                        XfCard {
                            XfTextField(name, { name = it }, label = "姓名", placeholder = "未识别，请填写")
                            Spacer(Modifier.height(XfDimens.itemGap))
                            XfTextField(company, { company = it }, label = "公司", placeholder = "留空保留已有公司")
                            Spacer(Modifier.height(XfDimens.itemGap))
                            XfTextField(phone, { phone = it }, label = "电话", placeholder = "未识别，留空保留已有号码", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                            if (matches.isNotEmpty()) {
                                Spacer(Modifier.height(XfDimens.itemGap))
                                XfStatusChip("可能是已有客户", tone = XfTone.Warning)
                                Spacer(Modifier.height(XfDimens.rowGap))
                                Column(verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    matches.forEach { c ->
                                        XfButton(
                                            "${c.name} · ${c.company.ifBlank { "未填写公司" }} · ${c.phone.ifBlank { "无电话" }}",
                                            { existingMode = true; selectedId = c.id },
                                            modifier = Modifier.fillMaxWidth(),
                                            kind = XfButtonKind.Secondary,
                                            small = true,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(XfDimens.itemGap))
                            XfSegmented(
                                options = listOf("更新已有客户", "新建客户"),
                                selectedIndex = if (existingMode) 0 else 1,
                                onSelect = { existingMode = it == 0 },
                            )
                            if (existingMode) {
                                Spacer(Modifier.height(XfDimens.itemGap))
                                Text("当前选择：${selected?.name ?: "请选择客户"}", style = XfType.small, color = colors.ink2)
                                Spacer(Modifier.height(XfDimens.rowGap))
                                XfSearchField(search, { search = it }, placeholder = "查找已有客户")
                                Spacer(Modifier.height(XfDimens.rowGap))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(XfDimens.rowGap), verticalArrangement = Arrangement.spacedBy(XfDimens.rowGap)) {
                                    customers.filter { search.isBlank() || listOf(it.name, it.company, it.phone).any { value -> value.contains(search, true) } }.forEach { c ->
                                        XfChip("${c.name} · ${c.company} · ${c.phone}", selected = c.id == selectedId, onClick = { selectedId = c.id })
                                    }
                                }
                            }
                            if (source.customerId.isNotBlank()) {
                                Spacer(Modifier.height(XfDimens.rowGap))
                                Text("本次保存将更新此沟通的客户关联，不复制沟通记录。", style = XfType.meta, color = colors.ink3)
                            }
                        }
                    }
                    if (!saved) preview?.let { proposed ->
                        XfCard {
                            XfSectionHeader("预览变更", subtitle = if (expected == null) "新建客户" else "更新客户：${expected!!.name}")
                            listOf(Triple("姓名", expected?.name.orEmpty(), proposed.name), Triple("公司", expected?.company.orEmpty(), proposed.company), Triple("电话", expected?.phone.orEmpty(), proposed.phone)).forEach { (label, before, after) ->
                                XfKeyValueRow(label, "${before.ifBlank { "未填写" }} → ${after.ifBlank { "未填写" }}${if (before == after) "（保持不变）" else ""}")
                            }
                            Text("关联原沟通：${source.title}；保留原文、截图及分析。不会自动新增待办或发送消息。", style = XfType.meta, color = colors.ink3)
                            XfButton("返回修改", { preview = null; failure = null }, enabled = !saving, kind = XfButtonKind.Ghost, small = true)
                        }
                    }
                    failure?.let { XfBanner(it, tone = XfTone.Danger, icon = XfIcons.Bell) }
                    Spacer(Modifier.height(XfDimens.rowGap))
                }
                if (!saved) {
                    XfButton(
                        if (saving) "正在保存…" else if (preview == null) "预览变更" else "确认并保存",
                        {
                            failure = null
                            if (preview == null) {
                                if (existingMode && selected == null) failure = "请先选择已有客户"
                                else {
                                    expected = if (existingMode) selected else null
                                    val base = expected ?: Customer(id = newId, name = "", source = "截图导入")
                                    val proposed = base.copy(name = name.trim().ifBlank { base.name }, company = company.trim().ifBlank { base.company }, phone = phone.trim().ifBlank { base.phone })
                                    val duplicate = customers.find { it.id != proposed.id && normalizedPhone(proposed.phone).isNotBlank() && normalizedPhone(it.phone) == normalizedPhone(proposed.phone) }
                                    if (proposed.name.isBlank()) failure = "请填写客户姓名"
                                    else if (duplicate != null) failure = "此电话已属于 ${duplicate.name}，请选择已有客户"
                                    else preview = proposed
                                }
                            } else {
                                saving = true
                                vm.saveScreenshotCustomer(source, expected, preview!!) { error ->
                                    saving = false
                                    if (error == null) saved = true else failure = error
                                }
                            }
                        },
                        enabled = !saving && vm.data != null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        loading = saving,
                    )
                }
            }
        }
    }
}
