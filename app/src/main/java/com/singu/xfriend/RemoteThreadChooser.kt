package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal fun remoteHistoryText(messages: JSONArray): String = (0 until messages.length()).map { index ->
    val message = messages.optJSONObject(index) ?: return@map ""
    val body = message.opt("content")
    val text = when (body) {
        is String -> body
        is JSONArray -> (0 until body.length()).mapNotNull { body.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("\n")
        else -> message.optString("text")
    }
    val role = message.optString("role", message.optString("type"))
    if(text.isBlank()) "" else "$role：$text"
}.filter(String::isNotBlank).joinToString("\n\n").takeLast(24000)

/** Selection carries the real host thread ID; preview never creates a replacement thread. */
@Composable internal fun RemoteThreadChooser(session: FriendRemoteSession?, resourceId: String, enabled: Boolean, selectedThreadId: String, onSelect: (String) -> Unit, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember(resourceId) { mutableStateOf(false) }
    var rows by remember(resourceId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var search by remember(resourceId) { mutableStateOf("") }
    var pending by remember(resourceId) { mutableStateOf("") }
    var title by remember(resourceId) { mutableStateOf("") }
    var preview by remember(resourceId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun load() {
        val current = session ?: return
        scope.launch {
            busy = true
            try { rows = withContext(Dispatchers.IO) { FriendRemoteClient().threads(current, resourceId) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onNotice(e.message ?: "读取电脑会话失败，请确认宿主已升级") }
            finally { busy = false }
        }
    }
    fun choose(id: String, label: String) {
        val current = session ?: return
        pending = id; title = label; preview = "正在读取真实会话历史…"
        scope.launch {
            busy = true
            try {
                val history = withContext(Dispatchers.IO) { FriendRemoteClient().threadHistory(current, resourceId, id) }
                if (pending == id) preview = remoteHistoryText(history.getJSONArray("messages")).ifBlank { "此会话尚无可显示的消息。" } + if (history.optBoolean("has_more")) "\n\n更早历史仍保留在电脑，续聊会使用原会话上下文。" else ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(pending==id){preview="";pending=""}; onNotice(e.message ?: "该会话暂不可读取，未切换会话") }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XfButton(if (selectedThreadId.isBlank()) "选择电脑已有会话" else "已选电脑会话 · 更换", { open = true; load() }, enabled = enabled && session != null && !busy, kind = XfButtonKind.Secondary, small = true)
        if(selectedThreadId.isNotBlank()) Text("原会话：$selectedThreadId", style=XfType.meta, color=xf.ink3)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text("选择电脑已有会话") },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                Text("选择后继续原 thread_id 和上下文，不创建影子会话。", style=XfType.meta)
                XfTextField(search, { search = it }, label="搜索标题或粘贴会话 ID", singleLine=true)
                LazyColumn(Modifier.weight(1f, false).heightIn(max=200.dp)) {
                    items(rows.filter { it.getString("title").contains(search,true) || it.getString("thread_id").contains(search,true) }, key={it.getString("thread_id")}) { row ->
                        XfButton((if(pending==row.getString("thread_id")) "● " else "")+row.getString("title"), { choose(row.getString("thread_id"),row.getString("title")) }, enabled=!busy, kind=XfButtonKind.Ghost, small=true)
                    }
                    if(search.matches(Regex("[a-zA-Z0-9_-]{1,128}")) && rows.none{it.getString("thread_id")==search}) item {
                        XfButton("按会话 ID 读取（仍验证所有权）", { choose(search,search) }, enabled=!busy, kind=XfButtonKind.Ghost, small=true)
                    }
                }
                if(preview.isNotBlank()) LazyColumn(Modifier.heightIn(max=180.dp)) { item { Text("$title\n\n$preview",style=XfType.small) } }
            }
        },
        confirmButton = { TextButton(enabled=pending.isNotBlank()&&!busy,onClick={onSelect(pending);open=false}) { Text("继续所选会话") } },
        dismissButton = { TextButton(onClick={open=false}) { Text("取消") } },
    )
}
