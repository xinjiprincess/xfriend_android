package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun RemoteModelChoices(models: List<RemoteModelOption>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (models.isEmpty()) Text("当前账号没有可选择的模型，请在电脑配置。", style = XfType.meta)
        if (selected.isNotBlank() && models.none { it.ref == selected }) Text("原绑定 $selected 当前不在授权目录中；未自动替换。", style = XfType.meta)
        models.forEach { model ->
            XfButton("${if (selected == model.ref) "● " else "○ "}${model.label} · ${model.ref}",
                { onSelect(model.ref) }, enabled = enabled, kind = XfButtonKind.Ghost, small = true)
        }
    }
}

/** Edits use the room's shared settings revision; a conflict leaves the form intact. */
@Composable internal fun RemoteRoomSettingsPanel(
    session: FriendRemoteSession?, room: JSONObject, participants: List<JSONObject>, enabled: Boolean,
    onChanged: (JSONObject) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val roomId = room.getString("room_id")
    var settingsOpen by remember(roomId) { mutableStateOf(false) }
    var seat by remember(roomId) { mutableStateOf<JSONObject?>(null) }
    var models by remember(session?.origin, roomId) { mutableStateOf<List<RemoteModelOption>>(emptyList()) }
    var mode by remember(roomId) { mutableStateOf("legacy") }
    var leader by remember(roomId) { mutableStateOf("") }
    var model by remember(roomId) { mutableStateOf("") }
    var seatMode by remember(roomId) { mutableStateOf("override") }
    var seatModel by remember(roomId) { mutableStateOf("") }
    var revision by remember(roomId) { mutableLongStateOf(0) }
    var busy by remember(roomId) { mutableStateOf(false) }
    var error by remember(roomId) { mutableStateOf("") }
    val owner = room.optString("role") == "owner"
    LaunchedEffect(settingsOpen, seat?.optString("participant_id"), session?.origin) {
        if (!settingsOpen && seat == null) return@LaunchedEffect
        val current = session ?: return@LaunchedEffect
        busy = true
        try { models = withContext(Dispatchers.IO) { FriendRemoteClient().models(current) }; error = "" }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "模型目录暂不可用" }
        finally { busy = false }
    }
    fun editSettings() {
        val value = RemoteRoomSettings.parse(room)
        mode = value.mode; leader = value.leader.orEmpty(); model = value.model.orEmpty(); revision = value.revision
        settingsOpen = true; error = ""
    }
    Text("协调方式：${room.optString("coordination_mode", "legacy")}", style = XfType.meta)
    if (owner) XfButton("群负责人 / 协调模型", ::editSettings, enabled = enabled, kind = XfButtonKind.Secondary, small = true)
    if (owner) participants.filter { it.optInt("enabled", 1) == 1 }.forEach { person ->
        XfButton("设置 ${person.optString("name")} 的本群模型", {
            seat = person
            seatMode = person.optString("model_mode", "override")
            seatModel = person.optString("model")
            revision = room.optLong("settings_revision")
            error = ""
        }, enabled = enabled, kind = XfButtonKind.Ghost, small = true)
    }
    if (settingsOpen || seat != null) AlertDialog(
        onDismissRequest = { if (!busy) { settingsOpen = false; seat = null } },
        title = { Text(if (seat == null) "群协调设置" else "本群席位模型") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只使用当前调用账号有权使用的模型；保存只影响后续任务。", style = XfType.meta)
                if (seat == null) {
                    listOf("leader" to "沿用明确负责人模型", "model" to "独立协调模型", "manual" to "手动 @ 分派", "legacy" to "保留原协调方式").forEach { (id, label) ->
                        XfButton("${if (mode == id) "● " else "○ "}$label", { mode = id }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    }
                    if (mode == "leader") participants.filter { it.optInt("enabled", 1) == 1 }.forEach { person ->
                        val id = person.getString("participant_id")
                        XfButton("${if (leader == id) "● " else "○ "}${person.optString("name")}", { leader = id }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    }
                    if (mode == "model") RemoteModelChoices(models, model, !busy) { model = it }
                } else {
                    if (seat?.optString("kind") != "model") XfButton("${if (seatMode == "inherit") "● " else "○ "}跟随专家配置", { seatMode = "inherit" }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    XfButton("${if (seatMode == "override") "● " else "○ "}仅本群覆盖", { seatMode = "override" }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    if (seatMode == "override") RemoteModelChoices(models, seatModel, !busy) { seatModel = it }
                }
                if (error.isNotBlank()) Text(error, style = XfType.meta)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val current = session ?: return@TextButton
            scope.launch {
                busy = true
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        val client = FriendRemoteClient()
                        val selectedSeat = seat
                        if (selectedSeat == null) client.roomSettings(current, roomId, RemoteRoomSettings(mode, leader.ifBlank { null }, model.ifBlank { null }, revision))
                        else client.roomParticipantModel(current, roomId, selectedSeat.getString("participant_id"), seatMode, seatModel.ifBlank { null }, revision)
                        client.room(current, roomId)
                    }
                    onChanged(snapshot); settingsOpen = false; seat = null; error = ""
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "设置未能保存，请刷新房间后重试" }
                finally { busy = false }
            }
        }) { Text("保存并读回") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { settingsOpen = false; seat = null }) { Text("取消") } },
    )
}
