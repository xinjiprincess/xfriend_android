package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Account-scoped background consolidation. It never delays or changes the chat task; a failed
 * memory call leaves the previous facts untouched and is reported only in the memory page.
 */
internal object MemoryWorker {
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val processedRuns = ConcurrentHashMap.newKeySet<String>()

    suspend fun afterRun(store: Store, scope: String, generation: Long, run: FriendRun) {
        if (BuildConfig.SALES_APP || run.rootContextId.isBlank() || run.agentParentId != null ||
            run.team || run.localRoomTurn != null || run.state != "completed") return
        if (!processedRuns.add("$scope:${run.id}")) return
        val now = System.currentTimeMillis()
        val due = synchronized(store) {
            store.memoryTurns = store.memoryTurns + 1
            val next = MemoryPolicy.shouldConsolidate(run.input, store.memoryTurns, store.memoryLastAttemptAt, now)
            if (next) store.memoryLastAttemptAt = now
            next
        }
        save(store, scope, generation)
        if (!due) return
        consolidate(store, scope, generation, run.resourceId, historyKey(run), now)
    }

    suspend fun consolidateNow(store: Store, scope: String, generation: Long, expertId: String, historyKey: String) {
        val now = System.currentTimeMillis()
        synchronized(store) { store.memoryLastAttemptAt = now }
        consolidate(store, scope, generation, expertId, historyKey, now)
    }

    private suspend fun consolidate(store: Store, scope: String, generation: Long,
                                    resourceId: String, historyKey: String, now: Long) {
        if (!running.add(scope)) return
        try {
            val expertId = synchronized(store) {
                store.experts.firstOrNull { it.id == resourceId }?.id ?: store.experts.firstOrNull()?.id
            } ?: return
            val (transcript, current) = synchronized(store) {
                val messages = store.messages[historyKey].orEmpty().takeLast(10).joinToString("\n") { message ->
                    val who = if (message.role == "user") "用户" else "助手"
                    "$who：${PhonePolicy.redact(message.content)}"
                }
                messages.take(12000) to store.memoryFacts.toList()
            }
            if (transcript.isBlank()) return
            synchronized(store) { store.memoryStatus = "正在后台整理长期记忆…" }
            save(store, scope, generation)
            val facts = AgentRuntime(store).organizeMemory(expertId, transcript, current)
            synchronized(store) {
                val cleaned = facts?.let { MemoryPolicy.sanitizeAll(it, now) }
                if (cleaned != null && (cleaned.isNotEmpty() || current.isEmpty())) {
                    store.memoryFacts = cleaned
                    store.memoryStatus = "长期记忆已更新：${store.memoryFacts.size} 条（${java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}）"
                } else {
                    store.memoryStatus = "记忆整理未返回有效结果；现有记忆保持不变。"
                }
            }
            save(store, scope, generation)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            synchronized(store) { store.memoryStatus = "记忆整理暂未完成；不影响任务结果，现有记忆保持不变。" }
            runCatching { save(store, scope, generation) }
        } finally {
            running.remove(scope)
        }
    }

    private suspend fun save(store: Store, scope: String, generation: Long) {
        FriendStoreRegistry.withAuthorization(scope, generation) {
            synchronized(store) { store.save() }
        }
    }

    private fun historyKey(run: FriendRun): String = if (run.team) "team:${run.resourceId}" else run.resourceId
}
