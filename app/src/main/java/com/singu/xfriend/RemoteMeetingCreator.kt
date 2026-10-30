package com.singu.xfriend

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Creates a desktop-hosted meeting. Drafts and uncertain submissions are isolated by account. */
@Composable internal fun RemoteMeetingCreator(
    session: FriendRemoteSession?, accountScope: String, enabled: Boolean,
    modelsEnabled: Boolean = false, materialsEnabled: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember(accountScope) {
        context.getSharedPreferences("friend-remote-meetings", android.content.Context.MODE_PRIVATE)
    }
    val key = "draft:" + (session?.let { remotePairedStorageScope(accountScope, it) } ?: remoteScopeKey(accountScope))
    val legacyKey = "draft:" + remoteScopeKey(accountScope)
    var open by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedHost by remember { mutableStateOf("@default") }
    var resources by remember { mutableStateOf<List<FriendRemoteResource>>(emptyList()) }
    var defaultHost by remember { mutableStateOf<JSONObject?>(null) }
    var modelOptions by remember { mutableStateOf<List<RemoteModelOption>>(emptyList()) }
    var hostModel by remember { mutableStateOf("") }
    var memberModels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var modelSeats by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingMember by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pendingFile by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var createdId by remember { mutableStateOf("") }
    fun persistDraft() {
        val json = JSONObject().put("title", title).put("topic", topic)
            .put("selected", JSONArray(selected.toList()))
            .put("selectedHost", selectedHost)
            .put("hostModel", hostModel).put("memberModels", JSONObject(memberModels)).put("modelSeats", JSONArray(modelSeats.toList()))
            .put("attachments", JSONArray().also { rows -> attachments.forEach(rows::put) })
            .put("pendingUri", pendingFile?.first?.toString())
            .put("pendingName", pendingFile?.second)
        prefs.edit().putString(key, json.toString()).apply()
    }
    fun loadDraft() {
        val saved = (prefs.getString(key, null) ?: prefs.getString("pending:$key", null))?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (saved != null) {
            title = saved.optString("title")
            topic = saved.optString("topic")
            selected = saved.optJSONArray("selected")?.let { rows ->
                (0 until rows.length()).map { rows.getString(it) }.toSet()
            } ?: ((saved.optJSONArray("expert_ids")?.let { values -> (0 until values.length()).map { values.getString(it) } }.orEmpty()) +
                saved.optJSONArray("team_ids")?.let { values -> (0 until values.length()).map { values.getString(it) } }.orEmpty()).toSet()
            selectedHost = saved.remoteNullable("selectedHost") ?: saved.remoteNullable("host_id") ?: "@default"
            hostModel = saved.remoteNullable("hostModel") ?: saved.remoteNullable("host_model").orEmpty()
            memberModels = (saved.optJSONObject("memberModels") ?: saved.optJSONObject("member_models"))?.let { models -> models.keys().asSequence().associateWith { models.getString(it) } }.orEmpty()
            modelSeats = saved.optJSONArray("modelSeats")?.let { models -> (0 until models.length()).map { models.getString(it) }.toSet() }
                ?: saved.optJSONArray("model_participants")?.remoteObjects()?.map { it.getString("model") }?.toSet().orEmpty()
            attachments = saved.optJSONArray("attachments")?.let { rows ->
                (0 until rows.length()).map { rows.getJSONObject(it) }
            } ?: saved.optJSONArray("material_ids")?.let { values -> (0 until values.length()).map { RemoteMaterial(values.getString(it), "已暂存资料", 0, "staged", "unknown", "").json() } }.orEmpty()
            val uri = saved.optString("pendingUri").takeIf(String::isNotBlank)?.let(android.net.Uri::parse)
            pendingFile = uri?.let { it to saved.optString("pendingName", "材料") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "meeting-material"
            pendingFile = uri to name
            runCatching { persistDraft() }.onFailure { error = it.message ?: "会议草稿保存失败" }
        }
    }
    LaunchedEffect(key) { loadDraft() }
    LaunchedEffect(open, session?.origin, modelsEnabled) {
        val current = session ?: return@LaunchedEffect
        if (!open) return@LaunchedEffect
        try {
            busy = true
            resources = withContext(Dispatchers.IO) { FriendRemoteClient().resources(current) }
            defaultHost = withContext(Dispatchers.IO) { FriendRemoteClient().meetingDefaultHost(current) }
            if (modelsEnabled) modelOptions = withContext(Dispatchers.IO) { FriendRemoteClient().models(current) }
            error = ""
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "无法读取会议主持人或专家列表" }
        finally { busy = false }
    }
    XfButton("新建电脑会议", { open = true; loadDraft() }, enabled = enabled && session != null,
        kind = XfButtonKind.Secondary, small = true)
    if (open) AlertDialog(
        onDismissRequest = { runCatching { persistDraft() }; open = false },
        title = { Text("新建电脑会议") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会议将在配对的电脑上运行。模型不可用时会由电脑明确提示设置，不会在手机端替换。", style = XfType.meta)
                if ((prefs.getString(legacyKey, null) != null || prefs.getString("pending:$legacyKey", null) != null) && prefs.getString(key, null) == null && prefs.getString("pending:$key", null) == null) {
                    Text("旧版会议草稿／待确认请求已隔离保留，无法确认其配对账号，不会转交当前配对提交。请在原电脑核对。", style = XfType.meta)
                    Text(runCatching { JSONObject(prefs.getString(legacyKey, null) ?: prefs.getString("pending:$legacyKey", null)!!).optString("topic") }.getOrDefault(""), style = XfType.meta)
                }
                if (prefs.getString("pending:$key", null) != null) Text("提交会沿用原会议请求编号和内容；请先核对电脑记录。", style = XfType.meta)
                XfTextField(title, { title = it.take(100); runCatching { persistDraft() } }, label = "会议名称", singleLine = true, enabled = !busy)
                XfTextField(topic, { topic = it.take(16000); runCatching { persistDraft() } }, label = "开场议题或说明", minLines = 3, enabled = !busy)
                defaultHost?.let { host ->
                    Text("默认主持人：${host.optJSONObject("host")?.optString("name").orEmpty()} · ${if (host.optBoolean("model_available")) "模型可用" else "需先在电脑设置模型"}", style = XfType.meta)
                }
                Text("主持人 / 专家负责人", style = XfType.bodyStrong)
                XfButton("${if (selectedHost == "@default") "● " else "○ "}电脑内置主持人", { selectedHost = "@default"; runCatching { persistDraft() } }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                resources.filter { it.kind == "expert" }.forEach { resource ->
                    XfButton("${if (selectedHost == resource.id) "● " else "○ "}${resource.name}", { selectedHost = resource.id; selected = selected - resource.id; runCatching { persistDraft() } }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                }
                if (modelsEnabled) {
                    Text("主持人模型（仅本次会议）", style = XfType.bodyStrong)
                    XfButton("${if (hostModel.isBlank()) "● " else "○ "}沿用主持人已有配置", { hostModel = ""; persistDraft() }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    RemoteModelChoices(modelOptions, hostModel, !busy) { hostModel = it; persistDraft() }
                }
                Text("选择讨论专家或团队（可多选）", style = XfType.bodyStrong)
                LazyColumn(Modifier.heightIn(max = 150.dp)) {
                    items(resources.filter { it.kind == "expert" || it.kind == "team" }, key = { it.id }) { resource ->
                        XfButton("${if (resource.id in selected) "☑" else "□"} ${resource.name} · ${resource.kind}", {
                            selected = if (resource.id in selected) selected - resource.id else selected + resource.id
                            if (resource.id == selectedHost) selected = selected - resource.id
                            runCatching { persistDraft() }
                        }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    }
                }
                if (modelsEnabled) {
                    resources.filter { it.id in selected && it.kind == "expert" }.forEach { resource ->
                        XfButton("${resource.name} · ${memberModels[resource.id] ?: "沿用专家模型"}",
                            { editingMember = if (editingMember == resource.id) "" else resource.id }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                        if (editingMember == resource.id) {
                            XfButton("沿用该专家已有配置", { memberModels = memberModels - resource.id; persistDraft() }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                            RemoteModelChoices(modelOptions, memberModels[resource.id].orEmpty(), !busy) { memberModels = memberModels + (resource.id to it); persistDraft() }
                        }
                    }
                    Text("纯模型席位（无需启动专家）", style = XfType.bodyStrong)
                    modelOptions.forEach { model ->
                        XfButton("${if (model.ref in modelSeats) "☑" else "□"} ${model.label}", {
                            modelSeats = if (model.ref in modelSeats) modelSeats - model.ref else (modelSeats + model.ref).take(20).toSet()
                            persistDraft()
                        }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    }
                }
                if (pendingFile != null) Text("待上传材料：${pendingFile!!.second}（上传失败时保留，可重试）", style = XfType.meta)
                attachments.forEach { Text("已上传：${it.optString("filename")} · ${RemoteMaterial.parse(it).readiness}", style = XfType.meta) }
                if (materialsEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XfButton("选择会议材料", { picker.launch(arrayOf("*/*")) }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    if (pendingFile != null) XfButton("上传材料", {
                        val current = session ?: return@XfButton
                        val chosen = pendingFile ?: return@XfButton
                        scope.launch {
                            busy = true
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val file = File.createTempFile("meeting-material-", ".upload", context.cacheDir)
                                    try {
                                        context.contentResolver.openInputStream(chosen.first)!!.use { input -> file.outputStream().use { copyRemoteFile(input, it) } }
                                        FriendRemoteClient().uploadMaterial(current, file, chosen.second,
                                            context.contentResolver.getType(chosen.first) ?: "application/octet-stream")
                                    } finally { file.delete() }
                                }
                                attachments = attachments + result
                                pendingFile = null
                                persistDraft()
                                error = ""
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = failure.message ?: "材料上传失败，所选材料已保留" }
                            finally { busy = false }
                        }
                    }, enabled = !busy, kind = XfButtonKind.Secondary, small = true)
                }
                if (error.isNotBlank()) Text(error, style = XfType.meta)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank() && topic.isNotBlank() && (selected.isNotEmpty() || modelSeats.isNotEmpty()) && (selectedHost != "@default" || hostModel.isNotBlank() || defaultHost?.optBoolean("model_available") == true),
                onClick = {
                    val current = session ?: return@TextButton
                    scope.launch {
                        busy = true
                        try {
                            val saved = prefs.getString("pending:$key", null)?.let(::JSONObject)
                            val request = saved ?: JSONObject()
                                .put("title", title).put("topic", topic)
                                .put("request_id", UUID.randomUUID().toString())
                                .put("expert_ids", JSONArray(resources.filter { it.id in selected && it.kind == "expert" }.map { it.id }))
                                .put("team_ids", JSONArray(resources.filter { it.id in selected && it.kind == "team" }.map { it.id }))
                                .put("material_ids", JSONArray(attachments.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }))
                            if (saved == null) {
                                if (selectedHost != "@default") request.put("host_id", selectedHost)
                                if (hostModel.isNotBlank()) request.put("host_model", hostModel)
                                request.put("member_models", JSONObject(memberModels.filterKeys { it in selected }))
                                request.put("model_participants", JSONArray(modelSeats.map { ref -> JSONObject().put("model", ref).put("name", modelOptions.find { it.ref == ref }?.label ?: ref) }))
                            }
                            if (saved == null) check(prefs.edit().putString("pending:$key", request.toString()).commit()) { "会议请求未能保存" }
                            val result = withContext(Dispatchers.IO) {
                                val client = FriendRemoteClient()
                                val created = client.createMeeting(current, request)
                                client.meeting(current, created.getString("id"))
                            }
                            createdId = result.optString("id")
                            check(prefs.edit().remove("pending:$key").remove(key).commit()) { "会议已建立，但手机草稿未能清除" }
                            title = ""; topic = ""; selected = emptySet(); selectedHost = "@default"; attachments = emptyList(); pendingFile = null
                            hostModel = ""; memberModels = emptyMap(); modelSeats = emptySet()
                            open = false; error = ""
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: "创建结果未知，可重试同一个请求" }
                        finally { busy = false }
                    }
                }) { Text("创建会议") }
        },
        dismissButton = { TextButton(onClick = { runCatching { persistDraft() }; open = false }) { Text("暂存并关闭") } },
    )
    if (createdId.isNotBlank()) AlertDialog(
        onDismissRequest = { createdId = "" },
        title = { Text("会议已创建") }, text = { Text("会议编号：$createdId。请在会议页明确确认后开始。") },
        confirmButton = { TextButton(onClick = { createdId = "" }) { Text("知道了") } },
    )
}
