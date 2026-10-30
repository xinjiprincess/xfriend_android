package com.singu.xfriend

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Separate .qa application only. No credentials or model output are bundled into an APK. */
internal suspend fun verifyFriendRuntime(app: Application) {
    val scope = "f".repeat(64)
    val input = File(app.filesDir, "qa-friend-model.json")
    val evidence = File(app.filesDir, "qa-friend-runtime-evidence.json")
    require(input.isFile) { "QA 模型配置未注入" }
    val config = try { JSONObject(input.readText()) } finally { input.delete() }
    val store = FriendStoreRegistry.get(app, scope)
    val expertId = "qa-runtime-agent"
    val marker = "XFRIEND_TOOL_VERIFIED"
    synchronized(store) {
        store.baseUrl = config.getString("base_url"); store.model = config.getString("model")
        store.apiKey = config.getString("key"); store.modelUseTools = true
        store.experts = store.experts.filterNot { it.id == expertId } + Expert(expertId, "真实工具验收", "Follow user instructions. Use real tools; never claim a file exists until a tool has checked it.")
        store.messages = store.messages - expertId; store.save()
    }
    val repo = FriendRunRepository(app, scope)
    AgentRuntime(store).preflight(expertId)
    val run = repo.enqueue(expertId, "Use write_file to write proof.txt containing exactly $marker. Then use read_file to read proof.txt. Reply with the content actually read. Do not skip tool calls.", false, UUID.randomUUID().toString(), ConversationMode.CRAFT)
    withContext(Dispatchers.Main) { FriendRunService.kick(app, scope) }
    try {
        withTimeout(240_000) {
            while (true) {
                delay(600)
                val current = repo.runs().first { it.id == run.id }
                if (!current.active) {
                    val file = workspacePath(File(store.workspaceRoot, expertId), "proof.txt")
                    val result = JSONObject().put("synthetic_only", true).put("provider_host", java.net.URI(store.baseUrl).host)
                        .put("model", store.model).put("state", current.state).put("error_code", current.errorCode)
                        .put("diagnostic_id", current.diagnosticId).put("file_written", file.isFile)
                        .put("file_content_verified", file.isFile && file.readText().trim() == marker)
                        .put("assistant_saved", store.messages[expertId].orEmpty().any { it.role == "assistant" && it.content.contains(marker) })
                        .put("partial_characters", current.partial.length)
                    result.put("passed", current.state == "completed" && result.getBoolean("file_content_verified") && result.getBoolean("assistant_saved"))
                    evidence.writeText(result.toString(2)); break
                }
            }
        }
    } finally {
        synchronized(store) { store.apiKey = ""; store.save() }
    }
}
