package com.singu.xfriend

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
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

/** Shows the server's current authorization manifest, never infers access from a path. */
@Composable internal fun RemoteMaterials(session: FriendRemoteSession?, kind: String, scopeId: String, enabled: Boolean) {
    if (scopeId.isBlank()) return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var manifest by remember(session?.origin, kind, scopeId) { mutableStateOf<JSONObject?>(null) }
    var selected by remember(session?.origin, kind, scopeId) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember(session?.origin, kind, scopeId) { mutableStateOf("") }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val current = session
        val material = selected
        if (uri != null && current != null && material != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("xf-material-", ".download", context.cacheDir)
                    try {
                        FriendRemoteClient().materialDownload(current, material.getString("id"), temporary)
                        context.contentResolver.openOutputStream(uri)?.use { output -> temporary.inputStream().use { it.copyTo(output) } }
                            ?: error("无法写入所选位置")
                    } finally { temporary.delete() }
                }
                notice = "资料已保存"
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice = failure.message ?: "资料下载失败，请重新检查授权" }
            finally { busy = false; selected = null }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XfButton("查看资料授权清单", {
            val current = session ?: return@XfButton
            scope.launch {
                busy = true
                try { manifest = withContext(Dispatchers.IO) { FriendRemoteClient().materialManifest(current, kind, scopeId) }; notice = "" }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { manifest = null; notice = failure.message ?: "无法读取当前资料授权" }
                finally { busy = false }
            }
        }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
        manifest?.let { value ->
            Text("共享原件与本账号执行快照；下载时电脑会重新核验当前权限。", style = XfType.meta)
            val materials = value.optJSONArray("materials") ?: JSONArray()
            for (index in 0 until materials.length()) {
                val material = materials.getJSONObject(index)
                val readable = RemoteMaterial.parse(material)
                Text("${readable.filename} · ${readable.readiness} · 原件${readable.size}字节${if (material.optBoolean("truncated")) " · 提取文本已截断" else ""}", style = XfType.small)
                XfButton("保存原件", { selected = material; saveAs.launch(material.optString("filename", "material")) }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            }
            val executions = value.optJSONArray("executions") ?: JSONArray()
            for (index in 0 until executions.length()) {
                val execution = executions.getJSONObject(index)
                Text("执行授权 ${execution.optString("agent_id")} / ${execution.optString("thread_id")} · ${execution.optString("status")}", style = XfType.meta)
                val files = execution.optJSONArray("files") ?: JSONArray()
                for (fileIndex in 0 until files.length()) {
                    val file = files.getJSONObject(fileIndex)
                    Text("${file.optString("workspace_path")}\nSHA256 ${file.optString("sha256")}", style = XfType.small)
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, style = XfType.meta)
    }
}
