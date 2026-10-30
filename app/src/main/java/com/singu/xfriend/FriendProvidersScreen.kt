package com.singu.xfriend

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal val friendProviderFormats = linkedMapOf(
    "openai_chat" to "OpenAI Chat Completions",
    "openai_responses" to "OpenAI Responses",
    "anthropic_messages" to "Anthropic Messages",
    "gemini_generate" to "Gemini GenerateContent",
)

private val deviceProviderTypes = setOf("codex", "github_copilot", "xai_oauth")
private val importProviderTypes = setOf("workbuddy_cn", "workbuddy_global", "gemini_pool", "claude_subscription", "gemini_subscription")
private val quotaProviderTypes = setOf("codex", "github_copilot", "workbuddy_cn", "workbuddy_global", "gemini_pool", "claude_subscription", "gemini_subscription")

internal fun friendProviderEditedCredentials(saved: Map<String, String>, apiKey: String): Map<String, String> =
    if (apiKey.isBlank()) saved.toMap() else saved + ("api_key" to apiKey.trim())

internal fun friendProviderVerificationUrl(providerType: String, raw: String): String? {
    val expected = when (providerType) {
        "codex" -> "auth.openai.com"
        "github_copilot" -> "github.com"
        "xai_oauth" -> "auth.x.ai"
        else -> return null
    }
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    return raw.takeIf { uri.scheme == "https" && uri.host == expected && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.rawFragment == null }
}

/** Read only a document explicitly chosen by the user; never place its contents in UI state. */
internal fun friendReadProviderDocument(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 1_048_576) { "Provider document exceeds size limit" }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

private fun friendProviderHost(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

private fun friendProviderFromPreset(preset: FriendProviderPreset): FriendModelConnection = FriendModelConnection(
    id = UUID.randomUUID().toString(), name = preset.name, providerId = preset.id, providerType = preset.providerType,
    apiFormat = preset.apiFormat, baseUrl = preset.baseUrl, model = preset.models.firstOrNull().orEmpty(),
    models = preset.models, credentials = emptyMap(), enabled = true, metadata = preset.metadata,
)

private fun friendCustomProvider(): FriendModelConnection = FriendModelConnection(
    id = UUID.randomUUID().toString(), name = "自定义连接", providerId = "custom", providerType = "api",
    apiFormat = "openai_chat", baseUrl = "", model = "", models = emptyList(), credentials = emptyMap(), enabled = true,
)

@Composable
internal fun FriendProvidersPage(s: Store, vm: FriendViewModel, back: () -> Unit, onLegacy: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<FriendModelConnection?>(null) }
    var catalog by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<FriendModelConnection?>(null) }
    var activatingId by remember { mutableStateOf<String?>(null) }
    val blocked = vm.providerBusy || vm.busy || reading
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val type = importType
        importType = null
        if (uri != null && type != null) scope.launch {
            reading = true; localError = null
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::friendReadProviderDocument)
                        ?: throw IllegalArgumentException("Unreadable document")
                }
                vm.importProviderDocument(type, text)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                localError = "无法读取所选 JSON 文件。请确认文件为 UTF-8 编码，且不超过 1 MB。"
            } finally { reading = false }
        }
    }
    fun import(type: String) {
        localError = null; importType = type
        document.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }
    fun activate(connection: FriendModelConnection) {
        activatingId = connection.id
        vm.selectProviderConnection(connection.id)
    }
    LaunchedEffect(s.activeModelConnectionId, vm.providerBusy, vm.providerError, activatingId) {
        if (activatingId != null && !vm.providerBusy && vm.providerError == null && s.activeModelConnectionId == activatingId) {
            activatingId = null; back()
        }
    }
    DisposableEffect(vm) { onDispose { if (vm.providerLogin != null) vm.cancelProviderLogin() } }
    BackHandler {
        if (editing != null) editing = null else if (catalog) catalog = false else back()
    }

    val currentDraft = editing?.let { draft -> s.modelConnections.firstOrNull { it.id == draft.id } ?: draft }
    if (currentDraft != null) {
        FriendProviderEditor(currentDraft, s, vm, blocked, localError, onClose = { editing = null },
            onActivate = ::activate, onImport = ::import)
    } else LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XfPageHeader(if (catalog) "添加模型连接" else "模型与供应商", subtitle = if (catalog)
                "选择 API 服务或个人账号渠道，再配置或授权。" else "保存多个连接，选择星助理当前使用的模型。")
        }
        item { FriendProviderNotices(vm, localError) }
        if (!catalog && onLegacy != null) item { XfButton("旧配置与资源导入", onLegacy, kind = XfButtonKind.Ghost, small = true) }
        vm.providerLogin?.let { login -> item("device-login") { FriendProviderDeviceLogin(vm, login, blocked) { localError = it } } }
        if (catalog) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XfButton("返回连接列表", { catalog = false }, kind = XfButtonKind.Ghost, small = true)
                    XfButton("自定义 API", { editing = friendCustomProvider() }, enabled = !blocked, kind = XfButtonKind.Secondary, small = true)
                }
            }
            item { XfSearchField(search, { search = it }, placeholder = "搜索供应商或账号渠道") }
            val presets = FriendProviderCatalog.presets.filter { search.isBlank() || "${it.name} ${it.providerType} ${it.id}".contains(search.trim(), ignoreCase = true) }
            items(presets, key = { "preset:${it.id}" }) { preset ->
                XfCard {
                    Text(preset.name, style = XfType.h3, color = xf.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(when {
                        preset.providerType == "onnx" -> "本机嵌入模型 · 此手机版暂不支持"
                        !preset.inference -> "账号额度渠道 · 不可用于聊天"
                        preset.providerType in deviceProviderTypes -> "个人账号 · 设备码授权"
                        preset.providerType in importProviderTypes -> "个人账号 · 选择凭据文档导入"
                        else -> friendProviderFormats[preset.apiFormat].orEmpty()
                    }, style = XfType.meta, color = xf.ink3)
                    if (preset.note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp)); Text(preset.note, style = XfType.small, color = xf.ink2)
                    }
                    if (preset.providerType != "onnx") {
                        Spacer(Modifier.height(10.dp))
                        XfButton(when (preset.providerType) {
                            in deviceProviderTypes -> "设备码登录"
                            in importProviderTypes -> "选择 JSON 导入"
                            else -> "配置连接"
                        }, {
                            when (preset.providerType) {
                                in deviceProviderTypes -> vm.startProviderLogin(preset.providerType)
                                in importProviderTypes -> import(preset.providerType)
                                else -> editing = friendProviderFromPreset(preset)
                            }
                        }, enabled = !blocked, kind = XfButtonKind.Secondary, small = true)
                    }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XfButton("添加供应商或账号", { catalog = true }, enabled = !blocked, small = true)
                    XfButton("自定义 API", { editing = friendCustomProvider() }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                }
            }
            if (s.modelConnections.isEmpty()) item {
                XfEmptyState(XfIcons.Plug, "还没有保存连接", "添加供应商 API 或个人账号后，选择当前模型。")
            }
            items(s.modelConnections, key = { "connection:${it.id}" }) { connection ->
                val canInfer = FriendProviderCatalog.canInfer(connection)
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(connection.name, style = XfType.h3, color = xf.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (connection.id == s.activeModelConnectionId) XfStatusChip("当前使用", tone = XfTone.Accent)
                        else if (!connection.enabled) XfStatusChip("已停用")
                        else if (!canInfer) XfStatusChip(if (connection.providerType in quotaProviderTypes) "仅额度" else "不支持聊天", tone = XfTone.Warning)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(if (canInfer) "${connection.model.ifBlank { "未选择模型" }} · ${friendProviderHost(connection.baseUrl)}"
                        else if (connection.providerType in quotaProviderTypes) "此渠道只查询账号额度，不作为聊天模型。"
                        else "此连接不支持手机端聊天推理。", style = XfType.small, color = xf.ink3)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XfButton("编辑", { editing = connection }, enabled = !blocked, kind = XfButtonKind.Secondary, small = true)
                        if (canInfer) XfButton("设为当前", { activate(connection) }, enabled = !blocked && connection.enabled && connection.model.isNotBlank(), small = true)
                        XfButton("删除", { deleting = connection }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                    }
                    if (connection.providerType in quotaProviderTypes) {
                        Spacer(Modifier.height(6.dp))
                        XfButton("查询账号额度", { vm.fetchProviderQuota(connection) }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                    }
                }
            }
        }
    }
    deleting?.let { connection -> XfDialog("删除连接？", { deleting = null }, "删除", {
        vm.deleteProviderConnection(connection.id); deleting = null
    }, danger = true) {
        Text(connection.name, style = XfType.bodyStrong)
        Text("本机保存的此连接及其凭据将被删除。", style = XfType.small)
    } }
}

@Composable
private fun FriendProviderNotices(vm: FriendViewModel, localError: String?) {
    val error = localError ?: vm.providerError
    if (error != null) XfBanner(error, tone = XfTone.Danger, icon = XfIcons.Bell)
    if (vm.providerNotice.isNotBlank()) XfBanner(vm.providerNotice, tone = XfTone.Neutral, icon = XfIcons.Bell)
}

@Composable
private fun FriendProviderEditor(connection: FriendModelConnection, s: Store, vm: FriendViewModel, blocked: Boolean,
    localError: String?, onClose: () -> Unit, onActivate: (FriendModelConnection) -> Unit, onImport: (String) -> Unit) {
    var name by remember(connection.id) { mutableStateOf(connection.name) }
    var format by remember(connection.id) { mutableStateOf(connection.apiFormat) }
    var url by remember(connection.id) { mutableStateOf(connection.baseUrl) }
    var model by remember(connection.id) { mutableStateOf(connection.model) }
    // Credentials must never enter rememberSaveable or be shown again from secure storage.
    var apiKey by remember(connection.id) { mutableStateOf("") }
    var enabled by remember(connection.id) { mutableStateOf(connection.enabled) }
    var savingGeneration by remember(connection.id) { mutableStateOf<Long?>(null) }
    var formatsOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var fetched by remember(connection.id) { mutableStateOf(false) }
    val native = connection.providerType in deviceProviderTypes || connection.providerType in importProviderTypes
    val canInfer = FriendProviderCatalog.canInfer(connection)
    val saved = s.modelConnections.any { it.id == connection.id }
    LaunchedEffect(connection.generation, vm.providerBusy, vm.providerError) {
        val requested = savingGeneration
        if (requested != null && !vm.providerBusy) {
            if (vm.providerError != null) savingGeneration = null
            else if (saved && connection.generation > requested) { apiKey = ""; savingGeneration = null }
        }
    }
    val preset = FriendProviderCatalog.find(connection.providerId)
    val options = (connection.models + preset?.models.orEmpty() + if (fetched && !blocked && vm.providerError == null) vm.providerModels else emptyList()).distinct()
    fun draft() = connection.copy(name = name.trim(), apiFormat = format, baseUrl = url.trim(), model = model.trim(),
        models = (options + model.trim()).filter { it.isNotBlank() }.distinct(),
        credentials = friendProviderEditedCredentials(connection.credentials, apiKey), enabled = enabled)
    val dirty = name.trim() != connection.name || format != connection.apiFormat || url.trim() != connection.baseUrl ||
        model.trim() != connection.model || enabled != connection.enabled || apiKey.isNotBlank()
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            XfButton("返回连接列表", onClose, kind = XfButtonKind.Ghost, small = true)
            Spacer(Modifier.height(8.dp))
            XfPageHeader(if (saved) "编辑模型连接" else "配置模型连接")
        }
        item { FriendProviderNotices(vm, localError) }
        item {
            XfCard {
                XfTextField(name, { name = it }, label = "连接名称", enabled = !blocked)
                Spacer(Modifier.height(12.dp))
                if (!native) {
                    Box {
                        XfButton("协议：${friendProviderFormats[format] ?: format}", { formatsOpen = true }, enabled = !blocked, kind = XfButtonKind.Secondary, small = true)
                        DropdownMenu(formatsOpen, { formatsOpen = false }) {
                            friendProviderFormats.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { format = id; formatsOpen = false }) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    XfTextField(url, { url = it }, label = "HTTPS 服务地址", placeholder = "https://服务域名/v1",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false), enabled = !blocked)
                    Spacer(Modifier.height(12.dp))
                    XfTextField(apiKey, { apiKey = it }, label = "API 密钥", placeholder = if (saved) "留空保留已保存密钥" else "填写服务商 API 密钥",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        visualTransformation = PasswordVisualTransformation(), enabled = !blocked,
                        supporting = if (connection.credentials.isEmpty()) "凭据只进入本机加密存储。" else "已保存凭据不会在此回显；留空不会删除。")
                } else {
                    Text("账号渠道：${preset?.name ?: connection.providerType}", style = XfType.small, color = xf.ink3)
                    Text(if (canInfer) "账号凭据由登录或导入流程管理。需要重新授权时，请使用对应的登录或导入入口。" else "此账号渠道只支持额度查询，不能设为聊天模型。", style = XfType.small, color = xf.ink3)
                    if (connection.providerType == "github_copilot") {
                        Spacer(Modifier.height(8.dp))
                        Box {
                            XfButton("协议：${friendProviderFormats[format] ?: format}", { formatsOpen = true }, enabled = !blocked, kind = XfButtonKind.Secondary, small = true)
                            DropdownMenu(formatsOpen, { formatsOpen = false }) {
                                listOf("openai_chat", "openai_responses").forEach { id ->
                                    DropdownMenuItem(text = { Text(friendProviderFormats.getValue(id)) }, onClick = { format = id; formatsOpen = false })
                                }
                            }
                        }
                    }
                    if (connection.providerType in importProviderTypes) {
                        Spacer(Modifier.height(8.dp))
                        XfButton("选择 JSON 导入", { onImport(connection.providerType) }, enabled = !blocked, kind = XfButtonKind.Secondary, small = true)
                    }
                }
                if (canInfer) {
                    Spacer(Modifier.height(12.dp))
                    XfTextField(model, { model = it }, label = "模型名称", placeholder = "可手动填写模型 ID", enabled = !blocked)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XfButton("获取模型列表", { fetched = true; vm.fetchProviderModels(draft()) }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                        if (options.isNotEmpty()) Box {
                            XfButton("选择模型", { modelsOpen = true }, enabled = !blocked, kind = XfButtonKind.Ghost, small = true)
                            DropdownMenu(modelsOpen, { modelsOpen = false }) {
                                options.take(100).forEach { id -> DropdownMenuItem(text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { model = id; modelsOpen = false }) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用此连接", style = XfType.body, color = xf.ink, modifier = Modifier.weight(1f))
                    Switch(enabled, { enabled = it }, enabled = !blocked)
                }
            }
        }
        if (canInfer) item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用工具调用", style = XfType.bodyStrong, color = xf.ink)
                        Text("当前聊天设置；测试会检查该模型是否返回工具调用。", style = XfType.meta, color = xf.ink3)
                    }
                    Switch(s.modelUseTools, { on -> vm.change { it.modelUseTools = on } }, enabled = !blocked)
                }
                Spacer(Modifier.height(8.dp))
                XfButton("测试连接", { vm.testProviderConnection(draft()) }, enabled = !blocked && model.isNotBlank(), kind = XfButtonKind.Secondary, small = true)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XfButton("保存连接", { savingGeneration = connection.generation; vm.saveProviderConnection(draft()) }, enabled = !blocked && name.isNotBlank() && (!canInfer || model.isNotBlank()))
                if (saved && canInfer) XfButton("设为当前并返回会话", { onActivate(connection) },
                    enabled = !blocked && !dirty && enabled && model.isNotBlank(), kind = XfButtonKind.Secondary)
                if (dirty && saved) Text("先保存修改，再切换当前连接。", style = XfType.meta, color = xf.ink3)
                if (connection.providerType in quotaProviderTypes) XfButton("查询账号额度", { vm.fetchProviderQuota(connection) }, enabled = !blocked, kind = XfButtonKind.Ghost)
            }
        }
    }
}

@Composable
private fun FriendProviderDeviceLogin(vm: FriendViewModel, login: ProviderDeviceLogin, blocked: Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    var now by remember(login.userCode) { mutableStateOf(System.currentTimeMillis() / 1000) }
    var nextPoll by remember(login.userCode) { mutableStateOf(0L) }
    LaunchedEffect(login.userCode) {
        while (true) { now = System.currentTimeMillis() / 1000; delay(1000) }
    }
    val remaining = (login.expiresAt - now).coerceAtLeast(0)
    val url = friendProviderVerificationUrl(login.providerType, login.verificationUrl)
    XfCard {
        Text("设备码授权", style = XfType.h3, color = xf.ink)
        Spacer(Modifier.height(6.dp))
        Text(login.userCode, style = XfType.mono, color = xf.accentInk)
        Text(if (remaining > 0) "授权码剩余 ${remaining / 60} 分 ${remaining % 60} 秒" else "授权码已到期，请取消后重新登录。", style = XfType.meta, color = xf.ink3)
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            Text(url, style = XfType.meta, color = xf.ink3)
            XfButton("打开授权页面", {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { onError("无法打开授权页面，请确认设备已安装浏览器。") }
            }, enabled = remaining > 0, kind = XfButtonKind.Secondary, small = true)
        } else Text("授权地址不符合此渠道的安全要求，请取消后重试。", style = XfType.small, color = xf.warningText)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XfButton(if (now < nextPoll) "${nextPoll - now} 秒后可查询" else "查看授权状态", {
                nextPoll = now + login.intervalSeconds.coerceAtLeast(1); vm.pollProviderLogin()
            }, enabled = !blocked && remaining > 0 && now >= nextPoll, kind = XfButtonKind.Secondary, small = true)
            XfButton("取消授权", { vm.cancelProviderLogin() }, kind = XfButtonKind.Ghost, small = true)
        }
    }
}
