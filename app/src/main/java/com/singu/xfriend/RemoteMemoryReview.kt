package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
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
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun RemoteMemoryReview(
    session: FriendRemoteSession?, resources: List<FriendRemoteResource>, accountScope: String, enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    val experts = remember(resources) { resources.filter { it.kind == "expert" } }
    var open by remember(accountScope) { mutableStateOf(false) }
    var resource by remember(accountScope) { mutableStateOf("") }
    var atoms by remember(accountScope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var candidates by remember(accountScope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var correction by remember { mutableStateOf<JSONObject?>(null) }
    var assertion by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun action(block: suspend (FriendRemoteSession) -> Unit) {
        val current = session ?: return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block(current) }; error = "" }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "记忆审阅暂不可用" }
            finally { busy = false }
        }
    }
    fun rows(value: JSONObject, vararg keys: String): List<JSONObject> {
        val data = keys.firstNotNullOfOrNull { value.optJSONArray(it) } ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }
    }
    fun reload() = action { current ->
        if (resource.isBlank()) return@action
        val client = FriendRemoteClient()
        atoms = rows(client.listMemories(current, resource, JSONObject().put("include_deprecated", false).put("limit", 30)), "atoms", "items", "results")
        candidates = rows(client.listMemoryCandidates(current, resource, JSONObject().put("status", "pending").put("limit", 30)), "candidates", "items", "results")
    }
    XfButton("电脑长期记忆审阅", { open = true }, enabled = enabled && session != null && experts.isNotEmpty(), kind = XfButtonKind.Secondary, small = true)
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("长期记忆审阅") },
        text = {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("只对所选专家的本体记忆执行明确的纠正、弃用或候选确认。", style = XfType.meta)
                experts.forEach { expert -> XfButton("${if (resource == expert.id) "● " else "○ "}${expert.name}", {
                    resource = expert.id; atoms = emptyList(); candidates = emptyList()
                    action { current ->
                        val client = FriendRemoteClient()
                        atoms = rows(client.listMemories(current, expert.id, JSONObject().put("include_deprecated", false).put("limit", 30)), "atoms", "items", "results")
                        candidates = rows(client.listMemoryCandidates(current, expert.id, JSONObject().put("status", "pending").put("limit", 30)), "candidates", "items", "results")
                    }
                }, enabled = !busy, kind = XfButtonKind.Ghost, small = true) }
                if (resource.isNotBlank()) {
                    XfButton("刷新记忆", { reload() }, enabled = !busy, kind = XfButtonKind.Ghost, small = true)
                    Text("现有记忆", style = XfType.bodyStrong)
                    LazyColumn(Modifier.heightIn(max = 190.dp)) {
                        items(atoms, key = { it.optString("atom_id", it.optString("id")) }) { atom ->
                            val id = atom.optString("atom_id", atom.optString("id"))
                            Text(atom.optString("assertion", atom.optString("content", atom.toString())), style = XfType.body)
                            Text("${atom.optString("kind")} · ${atom.optString("importance")}", style = XfType.meta)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                XfButton("纠正", { correction = JSONObject().put("type", "atom").put("id", id); assertion = atom.optString("assertion", atom.optString("content")); reason = "" }, enabled = !busy && id.isNotBlank(), kind = XfButtonKind.Secondary, small = true)
                                XfButton("弃用", { correction = JSONObject().put("type", "deprecate").put("id", id) }, enabled = !busy && id.isNotBlank(), kind = XfButtonKind.Ghost, small = true)
                            }
                        }
                    }
                    Text("待确认候选", style = XfType.bodyStrong)
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(candidates, key = { it.optString("candidate_id", it.optString("id")) }) { candidate ->
                            val id = candidate.optString("candidate_id", candidate.optString("id"))
                            Text(candidate.optString("assertion", candidate.optString("content", candidate.toString())), style = XfType.body)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                XfButton("确认写入", { correction = JSONObject().put("type", "promote").put("id", id) }, enabled = !busy && id.isNotBlank(), kind = XfButtonKind.Secondary, small = true)
                                XfButton("拒绝候选", { correction = JSONObject().put("type", "reject").put("id", id) }, enabled = !busy && id.isNotBlank(), kind = XfButtonKind.Ghost, small = true)
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, style = XfType.meta)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
    correction?.let { request ->
        val type = request.getString("type")
        AlertDialog(
            onDismissRequest = { correction = null },
            title = { Text(when (type) { "atom" -> "确认纠正记忆"; "deprecate" -> "确认弃用记忆"; "promote" -> "确认写入候选"; else -> "确认拒绝候选" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (type == "atom") XfTextField(assertion, { assertion = it }, label = "修正后的事实", minLines = 3)
                    if (type == "atom" || type == "deprecate" || type == "reject") XfTextField(reason, { reason = it }, label = "原因", minLines = 2)
                    Text("${request.optString("id")}\n\n操作会写入所选电脑专家的记忆，并保留审计记录。", style = XfType.meta)
                }
            },
            confirmButton = { TextButton(enabled = !busy && (type != "atom" || assertion.isNotBlank()), onClick = {
                action { current ->
                    val client = FriendRemoteClient()
                    when (type) {
                        "atom" -> client.correctMemory(current, resource, request.getString("id"), JSONObject().put("assertion", assertion).put("reason", reason))
                        "deprecate" -> client.deprecateMemory(current, resource, request.getString("id"), JSONObject().put("reason", reason).put("actor", "user"))
                        "promote" -> client.promoteMemoryCandidate(current, resource, request.getString("id"))
                        else -> client.rejectMemoryCandidate(current, resource, request.getString("id"), JSONObject().put("reason", reason).put("actor", "user"))
                    }
                    correction = null
                    val atomsBody = client.listMemories(current, resource, JSONObject().put("include_deprecated", false).put("limit", 30))
                    atoms = rows(atomsBody, "atoms", "items", "results")
                    candidates = rows(client.listMemoryCandidates(current, resource, JSONObject().put("status", "pending").put("limit", 30)), "candidates", "items", "results")
                }
            }) { Text("确认执行") } },
            dismissButton = { TextButton(onClick = { correction = null }) { Text("返回") } },
        )
    }
}
