package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.json.JSONObject
import java.util.UUID

/** Creates only the existing server-side proposal; execution still requires its exact confirmation. */
@Composable internal fun RemoteResponsibleCreator(
    session: FriendRemoteSession?, accountScope: String, enabled: Boolean, materialsEnabled: Boolean,
    onCreated: (JSONObject) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val key = session?.let { remotePairedStorageScope(accountScope, it) } ?: remoteScopeKey(accountScope)
    val prefs = remember(key) { context.getSharedPreferences("responsible-proposals", android.content.Context.MODE_PRIVATE) }
    var open by remember(key) { mutableStateOf(false) }
    var capabilities by remember(key) { mutableStateOf<RemoteResponsibleCapabilities?>(null) }
    var goal by remember(key) { mutableStateOf("") }
    var leader by remember(key) { mutableStateOf("") }
    var model by remember(key) { mutableStateOf("") }
    var allowed by remember(key) { mutableStateOf<Set<String>>(emptySet()) }
    var materials by remember(key) { mutableStateOf<List<RemoteMaterial>>(emptyList()) }
    var uploading by remember(key) { mutableStateOf(false) }
    var pending by remember(key) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf("") }
    LaunchedEffect(open, key) {
        if (!open) return@LaunchedEffect
        val current = session ?: return@LaunchedEffect
        busy = true
        try {
            capabilities = withContext(Dispatchers.IO) { FriendRemoteClient().responsibleCapabilities(current) }
            allowed = capabilities?.experts?.map { it.id }?.toSet().orEmpty()
            pending = prefs.getString(key, null)?.let(::JSONObject)
            pending?.let { goal = it.getString("goal") }
            error = ""
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "负责人任务能力暂不可用" }
        finally { busy = false }
    }
    XfButton("新建负责人任务方案", { open = true }, enabled = enabled && session != null, kind = XfButtonKind.Secondary, small = true)
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("负责人任务 · 先生成方案") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("电脑使用现有任务树生成有限分工方案；生成会调用模型，审阅并明确确认后才执行。", style = XfType.meta)
                if (pending != null) {
                    Text("上次提交内容已保存，重试会沿用原请求编号；请先检查任务列表，避免重复创建。", style = XfType.meta)
                    Text(pending!!.getString("goal"), style = XfType.body)
                    Text(pending!!.optString("leader_expert_id").takeUnless { it == "null" || it.isBlank() }
                        ?: pending!!.optString("leader_model"), style = XfType.meta)
                    XfButton("放弃本地待提交草稿并重新填写", {
                        runCatching { check(prefs.edit().remove(key).commit()) { "草稿未能清除" }; pending = null }
                            .onFailure { error = "草稿未能清除，请检查设备存储" }
                    }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                } else {
                    XfTextField(goal, { goal = it.take(16000) }, label = "任务目标", minLines = 3, enabled = !busy)
                    Text("明确选择负责人", style = XfType.bodyStrong)
                    capabilities?.experts?.forEach { expert ->
                        XfButton("${if (leader == expert.id) "● " else "○ "}${expert.name} · ${expert.model ?: "使用宿主已授权模型解析"}",
                            { leader = expert.id; model = "" }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    }
                    XfButton("${if (leader.isBlank()) "● " else "○ "}使用独立负责人模型", { leader = "" }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    if (leader.isBlank()) RemoteModelChoices(capabilities?.models.orEmpty().map { RemoteModelOption(it, it, "", "") }, model, !busy) { model = it }
                    Text("允许参与的专家（可全部取消，保留纯模型方案）", style = XfType.bodyStrong)
                    capabilities?.experts?.forEach { expert ->
                        XfButton("${if (expert.id in allowed) "☑" else "□"} ${expert.name}", {
                            allowed = if (expert.id in allowed) allowed - expert.id else allowed + expert.id
                        }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    }
                    if (materialsEnabled) RemoteStagedMaterials(session, materials, !busy, onBusyChange = { uploading = it }) { materials = it }
                }
                if (error.isNotBlank()) Text(error, style = XfType.meta)
            }
        },
        confirmButton = { TextButton(enabled = !busy && !uploading && capabilities != null && (pending != null || goal.isNotBlank() && (leader.isNotBlank() || model.isNotBlank())), onClick = {
            val current = session ?: return@TextButton
            scope.launch {
                busy = true
                try {
                    val request = pending ?: remoteResponsibleRequest(UUID.randomUUID().toString(), goal,
                        leader.ifBlank { null }, model.ifBlank { null }, allowed.toList(), materials.map { it.id })
                    if (pending == null) {
                        check(prefs.edit().putString(key, request.toString()).commit()) { "方案提交内容未能保存" }
                        pending = request
                    }
                    val created = withContext(Dispatchers.IO) { FriendRemoteClient().createResponsible(current, request) }
                    check(prefs.edit().remove(key).commit()) { "方案已创建，但本地待提交标记未能清除，请检查任务列表" }
                    onCreated(created)
                    pending = null; goal = ""; materials = emptyList(); open = false; error = ""
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "提交结果未知，内容和原请求编号已保留，可安全重试" }
                finally { busy = false }
            }
        }) { Text(if (pending == null) "生成待审方案" else "重试原申请") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("关闭") } },
    )
}
