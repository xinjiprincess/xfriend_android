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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native phone view for Octop's private rooms. Cloud rooms remain in CloudRoomsPanel. */
@Composable internal fun RemoteRooms(
    session: FriendRemoteSession?, accountScope: String, enabled: Boolean,
    modelSettingsEnabled: Boolean = false, materialsEnabled: Boolean = false, documentsEnabled: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember(accountScope) { context.getSharedPreferences("friend-remote-room-outbox", android.content.Context.MODE_PRIVATE) }
    var rooms by remember(accountScope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(accountScope) { mutableStateOf("") }
    var view by remember(accountScope) { mutableStateOf<JSONObject?>(null) }
    var participants by remember(accountScope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var resources by remember(accountScope) { mutableStateOf<List<FriendRemoteResource>>(emptyList()) }
    var selectedMembers by remember(accountScope) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedModels by remember(accountScope) { mutableStateOf<Set<String>>(emptySet()) }
    var modelOptions by remember(accountScope) { mutableStateOf<List<RemoteModelOption>>(emptyList()) }
    var floorMembers by remember(accountScope) { mutableStateOf<Set<String>>(emptySet()) }
    var floorText by remember(accountScope) { mutableStateOf("") }
    val storageScope = session?.let { remotePairedStorageScope(accountScope, it) } ?: remoteScopeKey(accountScope)
    var newRoomTitle by remember(storageScope) { mutableStateOf("") }
    var message by remember(storageScope, selected) { mutableStateOf("") }
    var materials by remember(storageScope, selected) { mutableStateOf<List<RemoteMaterial>>(emptyList()) }
    var uploading by remember(storageScope, selected) { mutableStateOf(false) }
    var mentions by remember(storageScope, selected) { mutableStateOf<Set<String>>(emptySet()) }
    var preflight by remember(storageScope, selected) { mutableStateOf<RemoteRoomPreflight?>(null) }
    var addMembers by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun launchAction(block: suspend (FriendRemoteSession) -> Unit) {
        val current = session ?: return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block(current) }; error = "" }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "本体房间暂不可用" }
            finally { busy = false }
        }
    }
    fun refresh(current: FriendRemoteSession) {
        launchAction { active ->
            rooms = FriendRemoteClient().roomList(active)
            if (rooms.none { it.optString("room_id") == selected }) selected = rooms.firstOrNull()?.optString("room_id").orEmpty()
            resources = FriendRemoteClient().resources(active).filter { it.kind == "expert" || it.kind == "team" }
        }
    }
    LaunchedEffect(session?.origin, accountScope) { if (session != null) refresh(session) }
    LaunchedEffect(addMembers, session?.origin, modelSettingsEnabled) {
        val current = session ?: return@LaunchedEffect
        if (addMembers && modelSettingsEnabled) {
            try { modelOptions = withContext(Dispatchers.IO) { FriendRemoteClient().models(current) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "模型目录暂不可用" }
        }
    }
    val activeRunId = view?.optJSONArray("runs")?.let { runs -> if (runs.length() > 0) runs.getJSONObject(runs.length() - 1).optString("run_id") else "" }.orEmpty()
    LaunchedEffect(session?.origin, selected, activeRunId) {
        val current = session ?: return@LaunchedEffect
        if (selected.isBlank()) return@LaunchedEffect
        val outboxKey = "$storageScope:$selected"
        val draft = prefs.getString("$outboxKey:draft", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (draft != null) {
            message = draft.optString("text")
            materials = draft.optJSONArray("materials")?.remoteObjects()?.map(RemoteMaterial::parse).orEmpty()
            mentions = draft.optJSONArray("mentions")?.let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }.orEmpty()
        }
        val savedMessage = prefs.getString(outboxKey, null)?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }
        if (draft == null && savedMessage != null) message = savedMessage
        val floorKey = "$storageScope:$selected:$activeRunId:floor"
        prefs.getString(floorKey, null)?.let { saved ->
            runCatching {
                val ids = JSONObject(saved).optJSONArray("participant_ids") ?: JSONArray()
                floorMembers = (0 until ids.length()).map { ids.getString(it) }.toSet()
                floorText = JSONObject(saved).optString("text")
            }
        }
        while (isActive) {
            try {
                val data = withContext(Dispatchers.IO) { FriendRemoteClient().room(current, selected) }
                view = data
                val rows = data.optJSONArray("participants") ?: JSONArray()
                participants = (0 until rows.length()).map { rows.getJSONObject(it) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "房间进度连接中断" }
            delay(3000)
        }
    }
    fun saveDraft(text: String = message, files: List<RemoteMaterial> = materials, targets: Set<String> = mentions) {
        if (selected.isBlank()) return
        val draft = JSONObject().put("text", text).put("materials", JSONArray(files.map { it.json() })).put("mentions", JSONArray(targets.toList()))
        prefs.edit().putString("$storageScope:$selected:draft", draft.toString()).apply()
    }
    XfCard {
        XfSectionHeader(title = "本体协作房间", subtitle = "仅显示配对电脑账号可访问的 Octop Room；云端房间在云协作页。")
        if (modelSettingsEnabled) {
            XfTextField(newRoomTitle, { newRoomTitle = it.take(100) }, label = "新群名称", enabled = enabled && !busy)
            XfButton("新建本体群", {
                launchAction { active ->
                    val client = FriendRemoteClient()
                    val created = client.createRoom(active, newRoomTitle)
                    val snapshot = client.room(active, created.getString("room_id"))
                    rooms = listOf(snapshot) + rooms.filterNot { it.optString("room_id") == snapshot.getString("room_id") }
                    selected = snapshot.getString("room_id"); view = snapshot
                    participants = snapshot.optJSONArray("participants")?.remoteObjects().orEmpty()
                    newRoomTitle = ""
                }
            }, enabled = enabled && !busy && newRoomTitle.isNotBlank(), kind = XfButtonKind.Secondary, small = true)
            Text("新群不自动指定负责人；纯真人消息无需模型，添加AI成员后请明确设置负责人或协调方式。", style = XfType.meta)
        }
        RemoteRoomInvitations(session, accountScope, enabled && !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XfButton("刷新", { session?.let(::refresh) }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            if (selected.isNotBlank()) XfButton("添加参会人", { addMembers = true }, enabled = enabled && !busy && rooms.find { it.optString("room_id") == selected }?.optString("role") == "owner", kind = XfButtonKind.Secondary, small = true)
        }
        rooms.forEach { room ->
            XfButton("${if (room.optString("room_id") == selected) "● " else ""}${room.optString("title")} · ${room.optString("role")}",
                { selected = room.optString("room_id") }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
        }
        val currentView = view
        if (currentView != null && currentView.optString("room_id") == selected) {
            if (modelSettingsEnabled) key(storageScope, selected) {
                RemoteRoomSettingsPanel(session, currentView, participants, enabled && !busy) { snapshot ->
                    view = snapshot
                    participants = snapshot.optJSONArray("participants")?.remoteObjects().orEmpty()
                    preflight = null
                    rooms = rooms.map { if (it.optString("room_id") == selected) snapshot else it }
                }
            }
            Text("AI 参与者", style = XfType.bodyStrong)
            participants.forEach { person ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteExpertAvatar(session, person.optString("agent_id"), person.optString("name"), resources.any { it.id == person.optString("agent_id") && it.avatarPath != null }, person.optString("kind") == "team")
                    Column(Modifier.weight(1f)) {
                        Text(person.optString("name"), style = XfType.body)
                        Text("${person.optString("kind")} · ${person.optString("model")}", style = XfType.meta)
                    }
                }
            }
            val messages = currentView.optJSONArray("messages") ?: JSONArray()
            for (index in maxOf(0, messages.length() - 30) until messages.length()) {
                val item = messages.getJSONObject(index)
                RemoteMessageCard(item.optString("name"), item.optString("role"), item.optString("content"))
            }
            val runs = currentView.optJSONArray("runs") ?: JSONArray()
            if (runs.length() > 0) {
                val activeRun = runs.getJSONObject(runs.length() - 1)
                val runId = activeRun.optString("run_id")
                val generation = activeRun.optInt("generation", 1)
                Text("群聊执行 · ${activeRun.optString("state")} · 第 $generation 代", style = XfType.bodyStrong)
                if (activeRun.optString("text").isNotBlank()) Text(activeRun.optString("text"), style = XfType.meta)
                if (activeRun.optString("state") in setOf("running", "paused", "waiting_user")) {
                    val controlKey = "$storageScope:$selected:$runId:control"
                    val oldControlKey = "${remoteScopeKey(accountScope)}:$selected:$runId:control"
                    val pendingControl = prefs.getString(controlKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (pendingControl != null) {
                            val pendingAction = pendingControl.optString("action")
                            XfButton("重试上次${when (pendingAction) { "pause" -> "暂停"; "resume" -> "继续"; "next" -> "下一轮"; else -> "结束" }}请求", {
                            launchAction { active ->
                                FriendRemoteClient().roomControl(active, selected, runId, pendingControl)
                                check(prefs.edit().remove(controlKey).commit())
                                view = FriendRemoteClient().room(active, selected)
                            }
                            }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
                        } else {
                            val actionSet = if (activeRun.optString("state") == "paused") listOf("resume", "next", "finish") else listOf("pause", "next", "finish")
                            actionSet.forEach { actionName -> XfButton(when (actionName) { "resume" -> "继续"; "next" -> "下一轮"; "finish" -> "结束"; else -> "暂停" }, {
                                launchAction { active ->
                                    val body = JSONObject().put("action", actionName).put("request_id", UUID.randomUUID().toString()).put("generation", generation)
                                    check(prefs.edit().putString(controlKey, body.toString()).commit()) { "群聊控制请求未保存" }
                                    FriendRemoteClient().roomControl(active, selected, runId, body)
                                    check(prefs.edit().remove(controlKey).commit())
                                    view = FriendRemoteClient().room(active, selected)
                                }
                            }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true) }
                        }
                    }
                    if (prefs.getString(oldControlKey, null) != null) Text("旧版控制请求已隔离保留，不会交给当前配对重试。", style = XfType.meta)
                    Text("提名下一轮发言者", style = XfType.bodyStrong)
                    val oldFloorKey = "${remoteScopeKey(accountScope)}:$selected:$runId:floor"
                    val floorKey = "$storageScope:$selected:$runId:floor"
                    val hasOldFloor = prefs.getString(oldFloorKey, null) != null && prefs.getString(floorKey, null) == null
                    if (hasOldFloor) Text("旧版发言提名已隔离保留，不会转交当前配对；可明确填写新的提名。", style = XfType.meta)
                    participants.filter { it.optInt("enabled", 1) == 1 }.forEach { person ->
                        val id = person.optString("participant_id")
                        XfButton("${if (id in floorMembers) "☑" else "□"} ${person.optString("name")}", {
                            floorMembers = if (id in floorMembers) floorMembers - id else (floorMembers + id).take(2).toSet()
                        }, enabled = !busy && id.isNotBlank(), kind = XfButtonKind.Ghost, small = true)
                    }
                    XfTextField(floorText, { floorText = it.take(4000) }, label = "主持人补充方向（可选）", minLines = 1)
                    XfButton("提交发言提名", {
                        launchAction { active ->
                            val key = "$storageScope:$selected:$runId:floor"
                            val body = prefs.getString(key, null)?.let(::JSONObject) ?: JSONObject()
                                .put("request_id", UUID.randomUUID().toString()).put("generation", generation)
                                .put("participant_ids", JSONArray(floorMembers.toList())).put("text", floorText)
                            if (prefs.getString(key, null) == null) check(prefs.edit().putString(key, body.toString()).commit()) { "发言提名请求未保存" }
                            FriendRemoteClient().roomFloor(active, selected, runId, body)
                            check(prefs.edit().remove(key).commit())
                            floorMembers = emptySet(); floorText = ""; view = FriendRemoteClient().room(active, selected)
                        }
                    }, enabled = enabled && !busy && floorMembers.isNotEmpty(), kind = XfButtonKind.Secondary, small = true)
                }
            }
            if (modelSettingsEnabled) {
                Text("@ 本次先发言的成员（手动分派模式必选）", style = XfType.bodyStrong)
                participants.filter { it.optInt("enabled", 1) == 1 }.forEach { person ->
                    val id = person.optString("participant_id")
                    XfButton("${if (id in mentions) "☑" else "□"} @${person.optString("name")}", {
                        mentions = if (id in mentions) mentions - id else mentions + id
                        preflight = null; saveDraft()
                    }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
                }
                XfButton("检查当前账号的群模型", {
                    launchAction { active -> preflight = FriendRemoteClient().roomPreflight(active, selected, mentions.toList()) }
                }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
                preflight?.checks?.forEach { check -> Text(check.description, style = XfType.meta) }
                if (preflight?.ready == true && preflight?.checks?.isEmpty() == true) Text("当前消息无需AI模型调度。", style = XfType.meta)
                if (preflight?.ready == false) Text("模型预检未通过；草稿与附件保留，请修正对应设置。", style = XfType.meta)
            }
            if (materialsEnabled) key(storageScope, selected) {
                RemoteStagedMaterials(session, materials, enabled && !busy, onBusyChange = { uploading = it }) { files -> materials = files; saveDraft(files = files) }
            }
            XfTextField(message, { message = it.take(32000); saveDraft(text = message) }, label = "发送到本体房间", minLines = 2, enabled = enabled && !busy)
            val hasPending = prefs.getString("$storageScope:$selected", null) != null
            if (hasPending) Text("上次发送结果尚未确认；安全重试先沿用原请求，当前新草稿会保留。", style = XfType.meta)
            val oldKey = "${remoteScopeKey(accountScope)}:$selected"
            if (prefs.getString(oldKey, null) != null && prefs.getString("$storageScope:$selected", null) == null) {
                Text("旧版待确认消息已隔离保留，无法确认其配对账号，不会转交当前配对发送。请在原电脑核对。", style = XfType.meta)
                Text(runCatching { JSONObject(prefs.getString(oldKey, null)!!).optString("text") }.getOrDefault(""), style = XfType.meta)
            }
            XfButton("发送 / 安全重试", {
                val current = session ?: return@XfButton
                val roomId = selected
                launchAction { active ->
                    val outboxKey = "$storageScope:$roomId"
                    val pending = prefs.getString(outboxKey, null)?.let(::JSONObject)
                        ?: remoteRoomMessage(message, UUID.randomUUID().toString(), materials.map { it.id }, mentions.toList())
                    val targets = pending.optJSONArray("mentions")?.let { list -> (0 until list.length()).map { list.getString(it) } }.orEmpty()
                    if (modelSettingsEnabled) {
                        val checked = FriendRemoteClient().roomPreflight(active, roomId, targets)
                        preflight = checked
                        if (!checked.ready) throw FriendRemoteFailure("MODEL_CONFIGURATION_REQUIRED", "群模型预检未通过，请检查上方对应角色；草稿和附件已保留")
                    }
                    if (prefs.getString(outboxKey, null) == null) check(prefs.edit().putString(outboxKey, pending.toString()).commit()) { "房间待发送消息未保存" }
                    FriendRemoteClient().roomSend(active, roomId, pending)
                    val pendingMaterials = pending.optJSONArray("material_ids")?.let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }.orEmpty()
                    val sameDraft = message == pending.optString("text") && materials.map { it.id }.toSet() == pendingMaterials && mentions == targets.toSet()
                    val cleanup = prefs.edit().remove(outboxKey)
                    if (sameDraft) cleanup.remove("$outboxKey:draft")
                    check(cleanup.commit()) { "消息已发送，但本地待发送标记未清除，请先核对原消息" }
                    if (sameDraft) {
                        message = ""; materials = emptyList(); mentions = emptySet()
                    }
                    view = FriendRemoteClient().room(active, roomId)
                }
            }, enabled = enabled && !busy && !uploading && (message.isNotBlank() || materials.isNotEmpty() || hasPending), kind = XfButtonKind.Primary, small = true)
        }
        if (error.isNotBlank()) Text(error, style = XfType.meta)
    }
    if (addMembers) AlertDialog(
        onDismissRequest = { addMembers = false; selectedMembers = emptySet(); selectedModels = emptySet() },
        title = { Text("选择本体 Room 参会人") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(resources, key = { it.id }) { resource ->
                    XfButton("${if (resource.id in selectedMembers) "☑" else "□"} ${resource.name} · ${resource.kind}", {
                        selectedMembers = if (resource.id in selectedMembers) selectedMembers - resource.id else selectedMembers + resource.id
                    }, kind = XfButtonKind.Ghost, small = true)
                }
                if (modelSettingsEnabled) items(modelOptions, key = { "model:${it.ref}" }) { model ->
                    XfButton("${if (model.ref in selectedModels) "☑" else "□"} 纯模型 · ${model.label}", {
                        selectedModels = if (model.ref in selectedModels) selectedModels - model.ref else selectedModels + model.ref
                    }, kind = XfButtonKind.Ghost, small = true)
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy && (selectedMembers.isNotEmpty() || selectedModels.isNotEmpty()) && selectedMembers.size + selectedModels.size <= 20, onClick = {
            val current = session ?: return@TextButton
            val roomId = selected
            launchAction { active ->
                val existing = FriendRemoteClient().roomParticipants(active, roomId).mapNotNull { it.optString("agent_id").takeIf(String::isNotBlank) }.toSet()
                val rows = resources.filter { it.id in selectedMembers && it.id !in existing }.map { resource ->
                    JSONObject().put("kind", resource.kind).put("agent_id", resource.id).put("name", resource.name)
                } + modelOptions.filter { it.ref in selectedModels }.map { model -> JSONObject().put("kind", "model").put("model", model.ref).put("name", model.label) }
                if (rows.isNotEmpty()) FriendRemoteClient().roomAddParticipants(active, roomId, JSONObject().put("participants", JSONArray(rows)))
                view = FriendRemoteClient().room(active, roomId)
                addMembers = false; selectedMembers = emptySet(); selectedModels = emptySet()
            }
        }) { Text("添加") } },
        dismissButton = { TextButton(onClick = { addMembers = false }) { Text("取消") } },
    )
    if (materialsEnabled) RemoteMaterials(session, "room", selected, enabled && selected.isNotBlank())
    if (documentsEnabled) RemoteReports(session, "rooms", selected, accountScope, enabled && selected.isNotBlank())
}
