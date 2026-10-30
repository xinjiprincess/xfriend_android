package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import java.util.UUID

/** Native configuration, observation and control for meetings hosted by the paired Octop. */
@Composable internal fun RemoteMeetings(session: FriendRemoteSession?, accountScope: String, enabled: Boolean, materialsEnabled: Boolean = false, documentsEnabled: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember(accountScope) { context.getSharedPreferences("friend-remote-meetings", android.content.Context.MODE_PRIVATE) }
    val storageScope = session?.let { remotePairedStorageScope(accountScope, it) } ?: remoteScopeKey(accountScope)
    var open by remember { mutableStateOf(false) }
    var rows by remember(session?.origin) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(session?.origin) { mutableStateOf("") }
    var snapshot by remember(session?.origin) { mutableStateOf<JSONObject?>(null) }
    var resources by remember(session?.origin) { mutableStateOf<List<FriendRemoteResource>>(emptyList()) }
    var chosenMembers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var floorSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var floorText by remember { mutableStateOf("") }
    var addingMembers by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    fun load() {
        val current = session ?: return
        scope.launch {
            busy = true
            try {
                rows = withContext(Dispatchers.IO) { FriendRemoteClient().meetings(current) }
                resources = withContext(Dispatchers.IO) { FriendRemoteClient().resources(current).filter { it.kind == "expert" } }
                if (rows.none { it.optString("id") == selected }) selected = rows.firstOrNull()?.optString("id").orEmpty()
                error = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "会议列表暂不可用，请确认宿主已升级" }
            finally { busy = false }
        }
    }
    LaunchedEffect(open, selected, session?.origin) {
        val current = session ?: return@LaunchedEffect
        if (!open || selected.isBlank()) return@LaunchedEffect
        val messageKey = "$storageScope:meeting:$selected:input"
        val savedInput = prefs.getString(messageKey, null)?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }
        if (!savedInput.isNullOrBlank()) input = savedInput
        val floorKey = "$storageScope:meeting:$selected:floor"
        prefs.getString(floorKey, null)?.let { saved ->
            runCatching {
                val body = JSONObject(saved)
                val ids = body.optJSONArray("participant_ids") ?: JSONArray()
                floorSelection = (0 until ids.length()).map { ids.getString(it) }.toSet()
                floorText = body.optString("text")
            }
        }
        snapshot = null
        while (isActive) {
            try { snapshot = withContext(Dispatchers.IO) { FriendRemoteClient().meeting(current, selected) }; error = "" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "会议进度连接中断，等待恢复" }
            delay(2500)
        }
    }
    XfButton("电脑会议 · 查看与控制", { open = true; load() }, enabled = enabled && session != null, kind = XfButtonKind.Secondary, small = true)
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("电脑会议") }, text = {
        Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会议和专家都由配对电脑运行。开始会议时电脑会准备主持人；查看不会启动发言。", style = XfType.meta)
            if (error.isNotBlank()) Text(error, style = XfType.meta)
            XfButton("刷新列表", { load() }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
            LazyColumn(Modifier.heightIn(max = 120.dp)) {
                items(rows, key = { it.optString("id") }) { row ->
                    val id = row.optString("id")
                    XfButton("${if (selected == id) "● " else ""}${row.optString("title", id)} · ${row.optString("state")}",
                        { selected = id }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                }
            }
            snapshot?.takeIf { it.optString("id") == selected }?.let { meeting ->
                val state = meeting.optString("state")
                val readiness = meeting.optString("readiness", "ready")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (readiness in setOf("queued", "starting")) XfStarMark(size = 22.dp)
                    Text("$state · 第${meeting.optInt("round")} / ${meeting.optInt("max_rounds")}轮 · ${meeting.optString("stage")} · ${when (readiness) { "queued" -> "排队"; "starting" -> "主持人准备中"; else -> "主持人就绪" }}", style = XfType.meta)
                }
                meeting.optJSONObject("host")?.let { host -> Text("主持人：${host.optString("name")} · ${host.optString("model")}", style = XfType.meta) }
                val configuredPeople = meeting.optJSONArray("participants") ?: JSONArray()
                for (index in 0 until configuredPeople.length()) {
                    val person = configuredPeople.getJSONObject(index)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteExpertAvatar(session, person.optString("id"), person.optString("name"), person.optString("icon_url").isNotBlank(), person.optString("kind") == "team")
                        Text("${person.optString("name")} · ${person.optString("model")}", style = XfType.meta)
                    }
                }
                val messages = meeting.optJSONArray("messages") ?: JSONArray()
                LazyColumn(Modifier.weight(1f, false).heightIn(max = 210.dp)) {
                    items((0 until messages.length()).map { messages.getJSONObject(it) }.takeLast(50)) { entry ->
                        RemoteMessageCard(entry.optString("name"), entry.optString("phase"), entry.optString("content"),
                            "第${entry.optInt("round")}轮", entry.optString("stage"), entry.optString("public_reasoning"), entry.optString("readiness", readiness))
                    }
                    if (meeting.optString("conclusion").isNotBlank()) item { Text("结论\n${meeting.optString("conclusion")}", style = XfType.body) }
                    if (meeting.optString("error").isNotBlank()) item { Text(meeting.optString("error"), style = XfType.meta) }
                }
                val pendingParticipants = meeting.optJSONArray("pending_participants") ?: JSONArray()
                if (pendingParticipants.length() > 0) {
                    Text("待下一轮加入", style = XfType.bodyStrong)
                    for (index in 0 until pendingParticipants.length()) {
                        val person = pendingParticipants.getJSONObject(index)
                        Text("${person.optString("name")} · 第${person.optInt("effective_round")}轮生效", style = XfType.meta)
                    }
                }
                if (meeting.optString("discussion_mode") == "free" && state == "running") {
                    Text("下一发言席位（最多两位）", style = XfType.bodyStrong)
                    val speakers = meeting.optJSONArray("participants") ?: JSONArray()
                    for (index in 0 until speakers.length()) {
                        val speaker = speakers.getJSONObject(index)
                        val id = speaker.optString("id")
                        XfButton("${if (id in floorSelection) "☑" else "□"} ${speaker.optString("name")}", {
                            floorSelection = if (id in floorSelection) floorSelection - id else (floorSelection + id).take(2).toSet()
                        }, enabled = !busy && id.isNotBlank(), kind = XfButtonKind.Ghost, small = true)
                    }
                    XfTextField(floorText, { floorText = it.take(4000) }, label = "发言方向（可选）", minLines = 1)
                    XfButton("提交发言提名", {
                        val current = session ?: return@XfButton
                        val id = selected
                        val floorKey = "$storageScope:meeting:$id:floor"
                        val body = prefs.getString(floorKey, null)?.let(::JSONObject) ?: JSONObject()
                            .put("request_id", UUID.randomUUID().toString()).put("generation", meeting.optInt("generation", 1))
                            .put("participant_ids", JSONArray(floorSelection.toList())).put("text", floorText).also {
                                check(prefs.edit().putString(floorKey, it.toString()).commit()) { "发言提名请求未保存" }
                            }
                        scope.launch {
                            busy = true
                            try {
                                snapshot = withContext(Dispatchers.IO) { FriendRemoteClient().meetingFloor(current, id, body) }
                                check(prefs.edit().remove(floorKey).commit())
                                floorSelection = emptySet(); floorText = ""; error = ""
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "发言提名结果未知，请刷新会议核对" }
                            finally { busy = false }
                        }
                    }, enabled = !busy && floorSelection.isNotEmpty(), kind = XfButtonKind.Secondary, small = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state in setOf("draft", "queued", "paused")) XfButton(if (state == "paused") "恢复会议" else "开始会议",
                        { confirmation = JSONObject().put("id", selected).put("action", "start").put("title", if (state == "paused") "确认恢复会议？" else "确认开始会议？") }, enabled = !busy, small = true)
                    if (state in setOf("running", "paused")) XfButton("停止会议", { confirmation = JSONObject().put("id", selected).put("action", "stop").put("title", "确认停止本次会议？") }, enabled = !busy, kind = XfButtonKind.Secondary, small = true)
                    XfButton("追加下一轮专家", {
                        val addKey = "$storageScope:meeting:$selected:add-members"
                        val pending = prefs.getString(addKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                        chosenMembers = pending?.optJSONArray("expert_ids")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() } ?: emptySet()
                        addingMembers = true
                    }, enabled = !busy && resources.isNotEmpty(), kind = XfButtonKind.Ghost, small = true)
                }
                XfTextField(input, { if (it.length <= 4000) input = it }, label = "补充说明", minLines = 2)
                XfButton("确认补充说明", { confirmation = JSONObject().put("id", selected).put("action", "input").put("text", input).put("title", "确认将这些说明交给会议？") }, enabled = !busy && input.isNotBlank(), kind = XfButtonKind.Ghost, small = true)
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
    if (addingMembers) AlertDialog(
        onDismissRequest = { addingMembers = false }, title = { Text("追加下一轮专家") },
        text = { Column(Modifier.heightIn(max = 360.dp)) {
            Text("当前发言会正常结束；所选专家将在下一轮开场加入。", style = XfType.meta)
            resources.forEach { expert -> XfButton("${if (expert.id in chosenMembers) "☑" else "□"} ${expert.name}", {
                chosenMembers = if (expert.id in chosenMembers) chosenMembers - expert.id else chosenMembers + expert.id
            }, enabled = !busy, kind = XfButtonKind.Ghost, small = true) }
        } },
        confirmButton = { TextButton(enabled = chosenMembers.isNotEmpty() && !busy, onClick = {
            val current = session ?: return@TextButton
            val id = selected
            val addKey = "$storageScope:meeting:$id:add-members"
            val pendingBody = prefs.getString(addKey, null)?.let(::JSONObject)
            val body = pendingBody ?: JSONObject().put("request_id", UUID.randomUUID().toString())
                .put("expert_ids", JSONArray(chosenMembers.toList())).also {
                    check(prefs.edit().putString(addKey, it.toString()).commit()) { "参会人变更请求未保存" }
                }
            scope.launch {
                busy = true
                try {
                    snapshot = withContext(Dispatchers.IO) { FriendRemoteClient().meetingAddParticipants(current, id, body) }
                    check(prefs.edit().remove(addKey).commit())
                    addingMembers = false; chosenMembers = emptySet(); error = ""
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "追加结果未知，请刷新会议核对后重试" }
                finally { busy = false }
            }
        }) { Text("确认追加") } },
        dismissButton = { TextButton(onClick = { addingMembers = false }) { Text("取消") } },
    )
    confirmation?.let { pending ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(pending.getString("title")) },
            text = { Text(if (pending.getString("action") == "input") pending.getString("text") else if (pending.getString("action") == "stop") "会取消当前发言和后续轮次，已产生的内容仍保留。" else "电脑会先准备主持人及其已配置模型，然后启动会议。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val current = session ?: return@TextButton
                scope.launch {
                    busy = true
                    try {
                        val action = pending.getString("action")
                        val body = when (action) {
                            "start" -> JSONObject().put("skip", false)
                            "input" -> {
                                val key = "$storageScope:meeting:${pending.getString("id")}:input"
                                val saved = prefs.getString(key, null)?.let(::JSONObject) ?: JSONObject()
                                    .put("text", pending.getString("text")).put("request_id", UUID.randomUUID().toString())
                                if (prefs.getString(key, null) == null) check(prefs.edit().putString(key, saved.toString()).commit())
                                saved
                            }
                            else -> JSONObject()
                        }
                        snapshot = withContext(Dispatchers.IO) { FriendRemoteClient().controlMeeting(current, pending.getString("id"), action, body) }
                        if (action == "input") {
                            val sentText = body.optString("text")
                            if (input == sentText) input = ""
                            prefs.edit().remove("$storageScope:meeting:${pending.getString("id")}:input").commit()
                        }
                        confirmation = null; error = ""
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "会议操作未确认，请刷新核对" }
                    finally { busy = false }
                }
            }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回核对") } })
    }
    if (materialsEnabled) RemoteMaterials(session, "meeting", selected, enabled && selected.isNotBlank())
    if (documentsEnabled) RemoteReports(session, "meetings", selected, accountScope, enabled && selected.isNotBlank())
}
