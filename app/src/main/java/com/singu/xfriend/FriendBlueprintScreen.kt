package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Parent entry point. Uses the existing account-scoped VM; no separate Store or credentials. */
@Composable fun FriendBlueprintScreen(friend: FriendViewModel, back: () -> Unit) {
    key(friend, friend.accountScope) { FriendBlueprintAccountScreen(friend, back) }
}

private fun Store.blueprintModel() = FriendBlueprintModel(baseUrl, model, apiKey.isNotBlank())

@Composable private fun FriendBlueprintAccountScreen(friend: FriendViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val colors = xf
    val jobs = rememberCoroutineScope()
    val scope = friend.accountScope
    val generation = remember { FriendStoreRegistry.generation(scope) }
    var input by remember { mutableStateOf("") }
    var blueprint by remember { mutableStateOf<FriendBlueprint?>(null) }
    var mappings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var plan by remember { mutableStateOf<FriendBlueprintPlan?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<FriendBlueprintPlan?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val store = friend.store
    val revision = friend.revision
    val phone = store?.let { synchronized(it) { it.blueprintModel() } }
    val authorized = FriendStoreRegistry.authorized(scope, generation)
    val locked = reading || friend.busy || pending != null
    fun resetPreview() { blueprint = null; mappings = emptyMap(); plan = null; acknowledged = false; message = null }
    fun parse() {
        resetPreview()
        jobs.launch {
            reading = true
            try { blueprint = withContext(Dispatchers.Default) { FriendBlueprint.parse(input) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { message = e.message ?: "蓝图无法解析" }
            finally { reading = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !locked && FriendStoreRegistry.authorized(scope, generation)) {
            resetPreview(); input = ""; reading = true
            jobs.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(bytes.size() + count <= FriendBlueprint.MAX_BYTES) { "蓝图不能超过 1 MiB" }
                                bytes.write(buffer, 0, count)
                            }
                            portableUtf8(bytes.toByteArray())
                        } ?: throw IllegalArgumentException("无法读取所选文件")
                    }
                    if (FriendStoreRegistry.authorized(scope, generation)) {
                        input = text
                        blueprint = withContext(Dispatchers.Default) { FriendBlueprint.parse(text) }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { message = e.message ?: "文件读取失败" }
                finally { reading = false }
            }
        }
    }
    LaunchedEffect(phone) { plan = null; acknowledged = false; mappings = emptyMap() }
    LaunchedEffect(friend.busy, revision, pending) {
        val submitted = pending
        if (submitted != null && !friend.busy) {
            val saved = store?.let { s -> synchronized(s) { submitted.experts.all { e -> s.experts.any { it.id == e.id } } } } == true
            message = if (saved) "已保存 ${submitted.experts.size} 位专家、${submitted.teams.size} 个团队、${submitted.skills.size} 个技能" else friend.error ?: "未保存，请重新预览后重试"
            if (saved) { input = ""; blueprint = null; plan = null; mappings = emptyMap(); acknowledged = false }
            pending = null
        }
    }
    BackHandler { back() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        XfPageHeader(
            title = "导入专家 JSON 蓝图",
            subtitle = "粘贴 AI 生成的 v1 蓝图或选择 JSON 文件。先预览，再确认保存；不会覆盖已有专家。",
        )
        if (!authorized) {
            XfBanner(text = "账号已退出或切换，请返回后重新打开。", tone = XfTone.Danger, icon = XfIcons.Bell)
        }
        XfCard {
            XfTextField(
                value = input,
                onValueChange = { value ->
                    if (value.length <= FriendBlueprint.MAX_BYTES) { input = value; resetPreview() }
                    else message = "蓝图不能超过 1 MiB"
                },
                label = "JSON 或 json 代码块",
                placeholder = "粘贴蓝图 JSON，或选择文件",
                singleLine = false,
                minLines = 5,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                XfButton(
                    text = "选择文件",
                    onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    enabled = !locked && authorized,
                    kind = XfButtonKind.Secondary,
                    icon = XfIcons.Folder,
                )
                XfButton(
                    text = "校验蓝图",
                    onClick = ::parse,
                    enabled = !locked && authorized && input.isNotBlank(),
                    icon = XfIcons.Check,
                )
            }
            if (reading || friend.busy) {
                Spacer(Modifier.height(12.dp))
                XfProgressBar(progress = null)
            }
            message?.let { text ->
                Spacer(Modifier.height(12.dp))
                XfBanner(text = text, tone = XfTone.Info, icon = XfIcons.Bell)
            }
        }
        blueprint?.let { parsed ->
            XfCard {
                XfSectionHeader(
                    title = "蓝图概览",
                    subtitle = "${parsed.experts.size} 位专家 · ${parsed.teams.size} 个团队 · ${parsed.experts.sumOf { it.skills.size }} 个技能",
                )
                Spacer(Modifier.height(8.dp))
                XfLabeledText(
                    label = "手机模型",
                    text = "${phone?.model ?: "尚未加载"}。仅映射到本账号当前手机模型，不根据模型名称猜测服务商。",
                    style = XfType.small,
                )
                if (parsed.requestedModels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    parsed.requestedModels.forEach { requested ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = mappings[requested] == phone?.model && mappings.containsKey(requested),
                                onCheckedChange = { checked ->
                                    mappings = if (checked && phone != null) mappings + (requested to phone.model) else mappings - requested
                                    plan = null; acknowledged = false
                                },
                                enabled = !locked && phone?.configured == true,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.accentSolid,
                                    uncheckedColor = colors.ink4,
                                    checkmarkColor = androidx.compose.ui.graphics.Color.White,
                                ),
                            )
                            Text(
                                "明确映射 $requested → ${phone?.model.orEmpty()}",
                                style = XfType.small,
                                color = colors.ink2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                XfButton(
                    text = "生成导入预览",
                    onClick = {
                        try { plan = parsed.plan(scope, generation, requireNotNull(phone), mappings); acknowledged = false; message = null }
                        catch (e: Exception) { message = e.message ?: "无法生成预览" }
                    },
                    enabled = !locked && authorized && phone != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        plan?.let { preview ->
            XfCard {
                XfSectionHeader(title = "保存前核对", subtitle = "确认后写入当前账号空间，不会覆盖已有资源。")
                Spacer(Modifier.height(6.dp))
                preview.experts.forEach { e ->
                    XfLabeledText(label = "专家", text = "${e.name}\n${e.prompt}", style = XfType.small)
                    Spacer(Modifier.height(8.dp))
                }
                preview.teams.forEach { t ->
                    XfLabeledText(
                        label = "团队",
                        text = "${t.name}\n成员：${t.members.joinToString { id -> preview.experts.first { it.id == id }.name }}",
                        style = XfType.small,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                preview.skills.forEach { s ->
                    XfLabeledText(label = "技能", text = "${s.name}\n${s.content}", style = XfType.small)
                    Spacer(Modifier.height(8.dp))
                }
                preview.notices.forEach { notice ->
                    XfBanner(text = notice, tone = XfTone.Warning, icon = XfIcons.Bell)
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        enabled = !locked,
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.accentSolid,
                            uncheckedColor = colors.ink4,
                            checkmarkColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    )
                    Text("我已核对内容、模型映射，接受上述字段不保存及手机运行方式。", style = XfType.small, color = colors.ink2)
                }
                Spacer(Modifier.height(8.dp))
                XfButton(
                    text = "确认保存到手机",
                    onClick = {
                        if (!FriendStoreRegistry.authorized(scope, generation) || friend.busy || friend.store !== store) {
                            message = "账号或资源库已变化，请重新打开"
                        } else {
                            pending = preview
                            friend.change { target ->
                                FriendStoreRegistry.withAuthorization(scope, generation) {
                                    preview.validateCommit(friend.accountScope, FriendStoreRegistry.generation(scope), target.blueprintModel(), acknowledged)
                                    require(preview.experts.none { e -> target.experts.any { it.id == e.id } }) { "该蓝图已经保存" }
                                    target.experts = target.experts + preview.experts
                                    target.teams = target.teams + preview.teams
                                    target.skills = target.skills + preview.skills
                                }
                            }
                        }
                    },
                    enabled = !locked && acknowledged && authorized && phone == preview.model,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            "蓝图只映射到本账号当前手机模型；缺失字段不会写入，电脑专属引擎与工具保留配置但不自动运行。",
            style = XfType.meta,
            color = colors.ink3,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
