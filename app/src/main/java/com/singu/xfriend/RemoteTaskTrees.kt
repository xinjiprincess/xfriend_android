package com.singu.xfriend

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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Observe saved desktop task trees and answer only the exact permission currently shown. */
@Composable internal fun RemoteTaskTrees(session: FriendRemoteSession?, accountScope: String, enabled: Boolean, responsibleEnabled: Boolean = false, materialsEnabled: Boolean = false) {
    val scope = rememberCoroutineScope()
    var rows by remember(accountScope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(accountScope) { mutableStateOf("") }
    var tree by remember(accountScope) { mutableStateOf<JSONObject?>(null) }
    var permission by remember(accountScope) { mutableStateOf<Triple<String, String, JSONObject>?>(null) }
    var proposal by remember(accountScope) { mutableStateOf<JSONObject?>(null) }
    var answerText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun perform(block: suspend (FriendRemoteSession) -> Unit) {
        val current = session ?: return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block(current) }; error = "" }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "电脑任务树暂不可用" }
            finally { busy = false }
        }
    }
    fun refresh() = perform { current ->
        rows = FriendRemoteClient().taskTrees(current)
        if (rows.none { it.optString("id") == selected }) selected = rows.firstOrNull()?.optString("id").orEmpty()
    }
    LaunchedEffect(session?.origin, accountScope) { refresh() }
    LaunchedEffect(session?.origin, selected) {
        val current = session ?: return@LaunchedEffect
        if (selected.isBlank()) return@LaunchedEffect
        while (isActive) {
            try { tree = withContext(Dispatchers.IO) { FriendRemoteClient().taskTree(current, selected) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "任务进度连接中断" }
            kotlinx.coroutines.delay(3000)
        }
    }
    XfCard {
        XfSectionHeader(title = "电脑任务树", subtitle = "查看任务恢复状态；审批只提交界面展示的精确选项。")
        if (responsibleEnabled) RemoteResponsibleCreator(session, accountScope, enabled && !busy, materialsEnabled) { created ->
            selected = created.getString("id"); tree = created
            rows = listOf(created) + rows.filterNot { it.optString("id") == selected }
        }
        XfButton("刷新任务树", { refresh() }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
        rows.take(10).forEach { row ->
            XfButton("${if (row.optString("id") == selected) "● " else ""}${row.optString("title")} · ${row.optString("state")}",
                { selected = row.optString("id") }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
        }
        tree?.takeIf { it.optString("id") == selected }?.let { snapshot ->
            snapshot.optJSONObject("responsible")?.let { responsible ->
                Text("负责人任务 · ${responsible.optString("stage")}", style = XfType.bodyStrong)
                Text(responsible.optString("goal"), style = XfType.body)
                responsible.optJSONObject("leader")?.let { leader -> Text("负责人：${leader.optString("expert_id")} · ${leader.optString("model")}", style = XfType.meta) }
                snapshot.optJSONObject("model_budget")?.let { budget -> Text("模型调用：已用 ${budget.optInt("used")} / ${budget.optInt("limit")}", style = XfType.meta) }
                if (responsible.optString("summary").isNotBlank()) Text(responsible.optString("summary"), style = XfType.body)
                if (responsible.optString("stage") == "awaiting_confirmation") XfButton("审阅负责人分工方案", { proposal = JSONObject(snapshot.toString()) }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
            }
            Text("${snapshot.optString("state")} · ${snapshot.optJSONObject("limits")?.optInt("total") ?: 12} 个任务上限", style = XfType.meta)
            val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                Text("${node.optString("title")} · ${node.optString("state")}", style = XfType.bodyStrong)
                if (node.optString("output").isNotBlank()) Text(node.optString("output"), style = XfType.small)
                val pending = node.optJSONObject("permission")
                if (pending != null) XfButton("查看待处理授权", { answerText = ""; permission = Triple(snapshot.getString("id"), node.optString("id"), JSONObject(pending.toString())) }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.optJSONObject("responsible")?.optString("stage") != "awaiting_confirmation" && (snapshot.optString("state") == "draft" || snapshot.optBoolean("can_resume_parent")))
                    XfButton("明确继续任务", { perform { current -> tree = FriendRemoteClient().taskAction(current, selected, "start") } }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
                if (snapshot.optString("state") !in setOf("succeeded", "failed", "cancelled", "interrupted"))
                    XfButton("取消任务树", { perform { current -> tree = FriendRemoteClient().taskAction(current, selected, "cancel") } }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            }
        }
        if (error.isNotBlank()) Text(error, style = XfType.meta)
    }
    permission?.let { (treeId, nodeId, pending) ->
        val request = pending.optJSONObject("request") ?: JSONObject()
        val dshOptions = request.optJSONArray("options")
        val isDshChoice = dshOptions != null && dshOptions.length() > 0
        val nativeActions = request.optJSONObject("request")?.optJSONArray("action_requests") ?: JSONArray()
        val isAsk = (0 until nativeActions.length()).any { nativeActions.optJSONObject(it)?.optString("name") == "ask_user_question" }
        val nativeToolApproval = pending.optString("engine") != "dsh" && nativeActions.length() > 0 && !isAsk
        AlertDialog(
            onDismissRequest = { permission = null },
            title = { Text("电脑任务审批") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("仅提交当前任务树中这条请求，不会推断或修改工具参数。", style = XfType.meta)
                    Text(pending.optJSONObject("request")?.toString(2) ?: "此请求没有可在手机展示的公开明细。", style = XfType.small)
                    if (isAsk) XfTextField(answerText, { answerText = it.take(16000) }, label = "回复此任务的问题", minLines = 2)
                    if (!isAsk && !isDshChoice && !nativeToolApproval) Text("此请求没有可在手机准确提交的选项，请在电脑处理。", style = XfType.meta)
                    if (isDshChoice) for (index in 0 until dshOptions!!.length()) {
                        val option = dshOptions.getJSONObject(index)
                        val exactId = option.optString("option_id", option.optString("optionId"))
                        if (exactId.isNotBlank()) XfButton(option.optString("label", option.optString("name", exactId)), {
                            val current = session ?: return@XfButton
                            perform { active ->
                                tree = FriendRemoteClient().answerTaskPermission(active, treeId, nodeId,
                                    JSONObject().put("pending_id", pending.getString("id")).put("option_id", exactId))
                                permission = null
                            }
                        }, enabled = enabled && !busy, kind = XfButtonKind.Secondary, small = true)
                    }
                }
            },
            confirmButton = {
                if (nativeToolApproval || isAsk) TextButton(enabled = enabled && !busy && (!isAsk || answerText.isNotBlank()), onClick = {
                    val current = session ?: return@TextButton
                    perform { active ->
                        tree = FriendRemoteClient().answerTaskPermission(active, treeId, nodeId,
                            JSONObject().put("pending_id", pending.getString("id")).put("decision", if (isAsk) "respond" else "approve").also { if (isAsk) it.put("message", answerText) })
                        permission = null
                    }
                }) { Text(if (isAsk) "回复此问题" else "允许本次") }
            },
            dismissButton = {
                TextButton(enabled = enabled && !busy, onClick = {
                    if (!nativeToolApproval) { permission = null; return@TextButton }
                    val current = session ?: return@TextButton
                    perform { active ->
                        tree = FriendRemoteClient().answerTaskPermission(active, treeId, nodeId,
                            JSONObject().put("pending_id", pending.getString("id")).put("decision", "reject"))
                        permission = null
                    }
                }) { Text(if (nativeToolApproval) "拒绝" else "关闭") }
            },
        )
    }
    proposal?.let { reviewed ->
        val responsible = reviewed.getJSONObject("responsible")
        val plan = responsible.optJSONObject("proposal")
        val nodes = plan?.optJSONArray("nodes") ?: JSONArray()
        AlertDialog(onDismissRequest = { proposal = null }, title = { Text("确认负责人分工方案") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(plan?.optString("title").orEmpty(), style = XfType.bodyStrong)
                Text("模型调用上限：${responsible.optInt("planned_model_call_limit")}；此确认仅启动当前方案，后续工具操作仍单独审批。", style = XfType.meta)
                for (index in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(index)
                    Text("${node.optString("id")} · ${node.optString("title")}", style = XfType.bodyStrong)
                    Text(node.optString("text"), style = XfType.body)
                    Text("专家 ${node.optString("expert_id")} · 模型 ${node.optString("model")} · 模式 ${node.optString("conversation_mode")}\n技能 ${node.optJSONArray("skills")}\n依赖 ${node.optJSONArray("depends_on")}\n所需资料 ${node.optJSONArray("required_inputs")}\n验收 ${node.optJSONArray("acceptance_criteria")}", style = XfType.small)
                }
            }
        }, confirmButton = { TextButton(enabled = enabled && !busy && nodes.length() > 0 && responsible.optString("proposal_digest").isNotBlank(), onClick = {
            perform { current ->
                tree = FriendRemoteClient().confirmResponsible(current, reviewed.getString("id"), JSONObject().put("confirm", true)
                    .put("generation", reviewed.getInt("generation")).put("revision", reviewed.getInt("revision")).put("proposal_digest", responsible.getString("proposal_digest")))
                proposal = null
            }
        }) { Text("确认此版本并开始") } }, dismissButton = { TextButton(onClick = { proposal = null }) { Text("返回审阅") } })
    }
}
