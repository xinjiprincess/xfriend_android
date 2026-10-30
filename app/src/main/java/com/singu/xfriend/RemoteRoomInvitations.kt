package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Current invitations come from the desktop; decision receipts stay account scoped on this phone. */
@Composable internal fun RemoteRoomInvitations(session: FriendRemoteSession?, accountScope: String, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val prefs = remember(accountScope) { context.getSharedPreferences("friend-remote-room-invitations", android.content.Context.MODE_PRIVATE) }
    val recordKey = "receipts:${session?.let { remotePairedStorageScope(accountScope, it) } ?: remoteScopeKey(accountScope)}"
    var invitations by remember(accountScope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var receipts by remember(accountScope) { mutableStateOf(prefs.getString(recordKey, "[]").orEmpty()) }
    var decision by remember(accountScope) { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(accountScope) { mutableStateOf("") }
    fun refresh() {
        val current = session ?: return
        scope.launch {
            busy = true
            try { invitations = withContext(Dispatchers.IO) { FriendRemoteClient().invitations(current) }; error = "" }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "无法读取当前群邀请" }
            finally { busy = false }
        }
    }
    LaunchedEffect(session?.origin, accountScope) { refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XfButton("刷新发给本账号的群邀请", { refresh() }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
        invitations.forEach { invitation ->
            Text("邀请加入：${invitation.optString("title")} · ${invitation.optString("room_id")}", style = XfType.body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XfButton("查看并接受", { decision = invitation to true }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
                XfButton("查看并拒绝", { decision = invitation to false }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            }
        }
        val saved = runCatching { JSONArray(receipts) }.getOrDefault(JSONArray())
        if (saved.length() > 0) Text("本手机的已确认邀请处理记录", style = XfType.meta)
        for (index in 0 until saved.length()) {
            val item = saved.getJSONObject(index)
            Text("${item.optString("title")} · ${if (item.optBoolean("accept")) "已接受" else "已拒绝"} · ${item.optString("invite_id")}", style = XfType.small)
        }
        if (error.isNotBlank()) Text(error, style = XfType.meta)
    }
    decision?.let { (invitation, accept) ->
        AlertDialog(onDismissRequest = { decision = null }, title = { Text(if (accept) "确认接受群邀请" else "确认拒绝群邀请") },
            text = { Text("${invitation.optString("title")}\n房间 ${invitation.optString("room_id")}\n邀请 ${invitation.optString("invite_id")}\n仅处理发给当前配对账号的这条邀请。") },
            confirmButton = { TextButton(enabled = enabled && !busy, onClick = {
                val current = session ?: return@TextButton
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { FriendRemoteClient().invitationDecision(current, invitation.getString("invite_id"), accept) }
                        val previous = runCatching { JSONArray(receipts) }.getOrDefault(JSONArray())
                        val next = JSONArray().put(JSONObject().put("invite_id", invitation.getString("invite_id")).put("title", invitation.optString("title")).put("accept", accept))
                        for (index in 0 until minOf(previous.length(), 29)) if (previous.getJSONObject(index).optString("invite_id") != invitation.getString("invite_id")) next.put(previous.getJSONObject(index))
                        receipts = next.toString()
                        check(prefs.edit().putString(recordKey, receipts).commit()) { "电脑已处理，但手机记录保存失败，请刷新房间核对" }
                        invitations = invitations.filter { it.optString("invite_id") != invitation.getString("invite_id") }
                        decision = null; error = ""
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "邀请处理结果未确认，请刷新核对" }
                    finally { busy = false }
                }
            }) { Text("确认") } }, dismissButton = { TextButton(onClick = { decision = null }) { Text("返回") } })
    }
}
