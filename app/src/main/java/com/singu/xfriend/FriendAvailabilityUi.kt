package com.singu.xfriend

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class FriendAvailability(val ready: Boolean = false, val label: String = "正在检查配置", val reason: String = "", val remote: Boolean = false)

/** Uses the same local configuration checks as send; this does not probe network health. */
@Composable
internal fun rememberFriendAvailability(s: Store): Map<String, FriendAvailability> {
    val fingerprint = listOf(s.experts, s.teams, s.baseUrl, s.model, s.apiKey, s.portablePackages, s.portableSecrets, s.modelUseTools)
    val results by produceState<Map<String, FriendAvailability>>(emptyMap(), fingerprint) {
        value = emptyMap()
        value = withContext(Dispatchers.Default) {
            val runtime = AgentRuntime(s)
            (s.experts.map { it.id to false } + s.teams.map { it.id to true }).associate { (id, team) ->
                val issue = runCatching { runtime.preflight(id, team) }.exceptionOrNull()
                val failure = issue?.let { friendModelFailure(it) }
                val remote = failure?.code in setOf("ENGINE_UNSUPPORTED", "PROVIDER_UNSUPPORTED")
                (if (team) "team:$id" else id) to when {
                    issue == null -> FriendAvailability(true, "手机可运行")
                    remote -> FriendAvailability(false, "需要电脑", failure?.userMessage.orEmpty(), true)
                    else -> FriendAvailability(false, if(failure?.code == "CONFIG_MODEL") "待配置模型" else "待配置", failure?.userMessage ?: "请检查专家配置")
                }
            }
        }
    }
    return results
}
