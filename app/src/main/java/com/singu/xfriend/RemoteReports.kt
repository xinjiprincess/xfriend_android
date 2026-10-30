package com.singu.xfriend

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Generation, review and download for a durable meeting or room host report. */
@Composable internal fun RemoteReports(session: FriendRemoteSession?, kind: String, scopeId: String, accountScope: String, enabled: Boolean) {
    if (scopeId.isBlank()) return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember(accountScope) { context.getSharedPreferences("friend-remote-reports", android.content.Context.MODE_PRIVATE) }
    val draftKey = remember(accountScope, session, kind, scopeId) { "${session?.let { remotePairedStorageScope(accountScope, it) } ?: remoteScopeKey(accountScope)}:$kind:$scopeId" }
    var rows by remember(accountScope, kind, scopeId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reportId by remember(accountScope, kind, scopeId) { mutableStateOf("") }
    var report by remember(accountScope, kind, scopeId) { mutableStateOf<JSONObject?>(null) }
    var preview by remember(accountScope, kind, scopeId) { mutableStateOf<JSONObject?>(null) }
    var transcriptPreview by remember(accountScope, kind, scopeId) { mutableStateOf<JSONObject?>(null) }
    var exportFormat by remember { mutableStateOf("") }
    var exportMode by remember { mutableStateOf("report") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(reportId) { preview = null }
    fun run(block: suspend (FriendRemoteSession) -> Unit) {
        val current = session ?: return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block(current) }; error = "" }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "报告操作暂不可用" }
            finally { busy = false }
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && exportFormat.isNotBlank()) {
            val current = session ?: return@rememberLauncherForActivityResult
            val id = reportId
            val digest = if (exportMode == "transcript") transcriptPreview?.optString("digest").orEmpty() else preview?.optString("digest").orEmpty()
            scope.launch {
                busy = true
                try {
                    val temp = File.createTempFile("xf-report-", ".$exportFormat", context.cacheDir)
                    try {
                        withContext(Dispatchers.IO) {
                            val body = JSONObject().put("format", exportFormat).put("mode", "all").put("indices", JSONArray())
                                .put("start", 0).put("end", 0).put("digest", digest)
                            if (exportMode == "transcript") FriendRemoteClient().exportTranscript(current, kind, scopeId, body, temp)
                            else FriendRemoteClient().exportReport(current, kind, scopeId, id, body, temp)
                            context.contentResolver.openOutputStream(uri)?.use { output -> temp.inputStream().use { input -> input.copyTo(output) } }
                                ?: throw java.io.IOException("无法写入所选文件位置")
                        }
                        notice = "报告已保存"
                    } finally { temp.delete() }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "报告导出失败" }
                finally { busy = false; exportFormat = ""; exportMode = "report" }
            }
        }
    }
    LaunchedEffect(session?.origin, accountScope, kind, scopeId) {
        val current = session ?: return@LaunchedEffect
        val savedId = prefs.getString("$draftKey:active", null).orEmpty()
        if (savedId.isNotBlank()) reportId = savedId
        while (isActive) {
            try {
                val client = FriendRemoteClient()
                rows = withContext(Dispatchers.IO) { client.listReports(current, kind, scopeId) }
                if (reportId.isBlank()) reportId = rows.firstOrNull()?.optString("report_id").orEmpty()
                if (reportId.isNotBlank()) report = withContext(Dispatchers.IO) { client.getReport(current, kind, scopeId, reportId) }
                error = ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "报告记录连接中断" }
            delay(if (report?.optString("state") in setOf("queued", "running") || report?.optBoolean("cancelling") == true) 2000 else 10000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            XfButton("预览会议逐字记录", {
                run { current -> transcriptPreview = FriendRemoteClient().reportSourcePreview(current, kind, scopeId) }
            }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            if (transcriptPreview != null) listOf("md", "docx", "pptx", "xlsx", "pdf", "html", "png").forEach { format ->
                XfButton("逐字 ${format.uppercase()}", {
                    exportMode = "transcript"; exportFormat = format
                    saveAs.launch("transcript.$format")
                }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            }
        }
        transcriptPreview?.let { transcript ->
            Text("完整逐字预览 · ${transcript.optJSONArray("messages")?.length() ?: 0} 段", style = XfType.meta)
            val messages = transcript.optJSONArray("messages") ?: JSONArray()
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items((0 until messages.length()).map { messages.getJSONObject(it) }) { item ->
                    Text("${item.optString("agent_id", item.optString("role"))}: ${item.optString("content")}", style = XfType.small)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XfButton("主持人整理报告", {
                run { current ->
                    val client = FriendRemoteClient()
                    val source = client.reportSourcePreview(current, kind, scopeId)
                    val pendingKey = "$draftKey:pending"
                    val body = prefs.getString(pendingKey, null)?.let(::JSONObject)
                        ?: JSONObject().put("request_id", UUID.randomUUID().toString())
                            .put("digest", source.getString("digest")).put("mode", "all")
                    if (prefs.getString(pendingKey, null) == null) check(prefs.edit().putString(pendingKey, body.toString()).commit()) { "报告请求未保存，尚未提交" }
                    val created = client.createReport(current, kind, scopeId, body)
                    val id = created.optString("report_id", created.optString("id"))
                    check(id.isNotBlank()) { "报告创建响应缺少标识，保留原请求供安全重试" }
                    check(prefs.edit().putString("$draftKey:active", id).remove(pendingKey).commit()) { "报告已创建，但手机未能保存恢复标记" }
                    reportId = id
                    report = created
                    notice = "报告已排队；可离开页面，回来后按账号和房间恢复进度。"
                }
            }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (rows.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.take(5).forEach { row ->
                    val id = row.optString("report_id", row.optString("id"))
                    XfButton("${if (id == reportId) "● " else ""}${row.optString("state")}", {
                        reportId = id; preview = null
                        check(prefs.edit().putString("$draftKey:active", id).commit())
                        run { current -> report = FriendRemoteClient().getReport(current, kind, scopeId, id) }
                    }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                }
            }
        }
        report?.let { value ->
            val state = value.optString("state")
            Text("报告 $state${if (value.optBoolean("cancelling")) " · 正在停止" else ""}", style = XfType.meta)
            if (state in setOf("queued", "running") && !value.optBoolean("cancelling")) XfButton("停止生成", {
                run { current -> report = FriendRemoteClient().cancelReport(current, kind, scopeId, reportId) }
            }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            val result = value.optJSONObject("report")
            if (result != null) {
                Text(result.optString("title"), style = XfType.bodyStrong)
                @Composable fun renderList(title: String, key: String) {
                    val values = result.optJSONArray(key) ?: return
                    Text(title, style = XfType.bodyStrong)
                    for (index in 0 until values.length()) {
                        val item = values.opt(index)
                        Text(if (item is JSONObject) item.optString("text", item.toString()) else item.toString(), style = XfType.body)
                        if (item is JSONObject) {
                            val citations = item.optJSONArray("source_indices")
                            if (citations != null) Text("引用原文段落：$citations", style = XfType.meta)
                            if (item.optString("owner").isNotBlank() || item.optString("due").isNotBlank()) Text("${item.optString("owner")} · ${item.optString("due")}", style = XfType.meta)
                        }
                    }
                }
                renderList("摘要", "summary")
                renderList("事实", "facts")
                renderList("决定", "decisions")
                renderList("分歧", "disagreements")
                renderList("行动项", "actions")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XfButton("查看导出预览", {
                        run { current -> preview = FriendRemoteClient().reportPreview(current, kind, scopeId, reportId) }
                    }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
                    listOf("md", "docx", "pptx", "xlsx", "pdf", "html", "png").forEach { format ->
                        XfButton(format.uppercase(), { exportMode = "report"; exportFormat = format; saveAs.launch("${value.optJSONObject("report")?.optString("title")?.ifBlank { "host-report" } ?: "host-report"}.$format") }, enabled = enabled && !busy && preview != null, kind = XfButtonKind.Secondary, small = true)
                    }
                }
                preview?.let { documentPreview ->
                    Text("导出内容预览 · ${documentPreview.optJSONArray("messages")?.length() ?: 0} 段 · digest ${documentPreview.optString("digest").take(12)}", style = XfType.meta)
                    val messages = documentPreview.optJSONArray("messages") ?: JSONArray()
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items((0 until messages.length()).map { messages.getJSONObject(it) }) { item ->
                            Text("${item.optString("agent_id", item.optString("role"))}: ${item.optString("content")}", style = XfType.small)
                        }
                    }
                }
            }
            if (value.optString("error").isNotBlank()) Text(value.optString("error"), style = XfType.meta)
        }
        if (notice.isNotBlank()) Text(notice, style = XfType.meta)
        if (error.isNotBlank()) Text(error, style = XfType.meta)
    }
}
