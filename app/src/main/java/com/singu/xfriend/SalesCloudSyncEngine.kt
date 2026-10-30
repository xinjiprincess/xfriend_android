package com.singu.xfriend

import org.json.JSONObject

internal interface SalesCloudLocal {
    fun load(): SalesCloudState
    fun update(action: (SalesCloudState, Map<String, SalesCloudEntity>) -> SalesCloudState): SalesCloudState
    fun pull(response: JSONObject): SalesCloudState
    fun resolve(conflictId: String, keepLocal: Boolean): SalesCloudState
}

internal interface SalesCloudTransport {
    suspend fun pull(cursor: Long, limit: Int = 100): JSONObject
    suspend fun push(operations: List<SalesCloudOperation>): JSONObject
}

internal class SalesCloudSyncEngine(private val local: SalesCloudLocal, private val transport: SalesCloudTransport,
    private val identity: SalesCloudIdentity, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun sync(): SalesCloudState {
        local.update { state, snapshot -> state.capture(snapshot) }
        pullAll()
        while (true) {
            val state = local.update { current, snapshot -> current.capture(snapshot) }
            if (state.outbox.isEmpty()) break
            val operations = salesCloudBatch(identity, state.outbox)
            // The operation IDs/content already exist durably; network failures never remove them.
            val response = transport.push(operations)
            local.update { current, snapshot -> current.acknowledge(operations, response, snapshot) }
        }
        pullAll()
        return local.update { state, _ -> state.copy(lastSuccess = now()) }
    }

    private suspend fun pullAll() {
        var reset = false
        var pageLimit = 100
        while (true) {
            val before = local.load()
            val response = try { transport.pull(before.cursor, pageLimit) } catch (failure: SalesCloudHttpException) {
                if (failure.code == "response_too_large" && pageLimit > 1) { pageLimit = 1; continue }
                if (failure.code != "invalid_cursor" || reset) throw failure
                // Server restore: retain proposals and force explicit reconciliation of existing data.
                local.update { state, snapshot -> state.copy(cursor = 0,
                    known = state.known.mapValues { it.value.copy(revision = 0) },
                    observed = state.observed.mapValues { "reconcile" }).capture(snapshot) }
                reset = true
                continue
            }
            val next = local.pull(response)
            if (!response.getBoolean("has_more")) return
            require(next.cursor > before.cursor) { "云端分页没有前进，已停止以保留本地数据" }
        }
    }
}
