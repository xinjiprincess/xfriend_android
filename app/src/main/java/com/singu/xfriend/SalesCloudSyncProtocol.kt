package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SalesCloudIdentity(val scope: String, val owner: String, val space: String, val device: String)
internal class SalesCloudHttpException(val status: Int, val code: String, message: String) : Exception(message)
internal val salesCloudTypes = setOf("customer", "communication", "opportunity", "followup", "model_profile", "expert", "team", "skill", "knowledge")

/** Stable comparison independent of JSONObject's key iteration order. */
internal fun salesCloudCanonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + salesCloudCanonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { salesCloudCanonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/** Preserve desktop extensions (including _xfpack) without inheriting secret-export consent. */
internal fun salesCloudOutgoingPayload(local: SalesCloudEntity, previous: SalesCloudEntity?): String {
    fun overlay(base: JSONObject, update: JSONObject): JSONObject = JSONObject(base.toString()).also { result ->
        update.keys().forEach { key ->
            val value = update.get(key)
            result.put(key, if (value is JSONObject && result.opt(key) is JSONObject) overlay(result.getJSONObject(key), value) else value)
        }
    }
    val current = JSONObject(local.payload)
    val merged = overlay(previous?.let { JSONObject(it.payload) } ?: JSONObject(), current)
    val optIn = current.optBoolean("syncSecret") || current.optBoolean("sync_secrets")
    val hasConsent = local.type == "model_profile" || merged.has("syncSecret") || merged.has("sync_secrets")
    merged.remove("syncSecret"); if (hasConsent) merged.put("sync_secrets", optIn)
    if (local.type == "model_profile" && current.has("apiKey")) merged.remove("key")
    val auth = setOf("token", "accesstoken", "refreshtoken", "authorization")
    val sensitive = setOf("apikey", "apisecret", "secret", "secrets", "password", "credential", "credentials", "privatekey")
    fun clean(value: Any?): Any? {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { field ->
                val normalized = field.lowercase().replace("_", "").replace("-", "")
                if (normalized in auth || (!optIn && (normalized in sensitive || (local.type == "model_profile" && normalized == "key")))) value.remove(field)
                else clean(value.opt(field))
            }
            is JSONArray -> (0 until value.length()).forEach { clean(value.opt(it)) }
        }
        return value
    }
    clean(merged)
    return salesCloudCanonical(merged)
}

internal data class SalesCloudEntity(val type: String, val id: String, val payload: String = "{}", val deleted: Boolean = false, val revision: Long = 0) {
    init { require(type in salesCloudTypes && id.isNotBlank() && id.length <= 128 && revision >= 0) { "云端实体格式无效" } }
    val key get() = "$type/$id"
    val fingerprint get() = if (deleted) "deleted" else salesCloudCanonical(JSONObject(payload))
    fun json() = JSONObject().put("entity_type", type).put("entity_id", id).put("payload", JSONObject(payload)).put("deleted", deleted).put("revision", revision)
    companion object {
        fun read(j: JSONObject) = SalesCloudEntity(j.getString("entity_type"), j.getString("entity_id"),
            salesCloudCanonical(j.getJSONObject("payload")), j.getBoolean("deleted"), j.getLong("revision"))
    }
}

internal data class SalesCloudOperation(val id: String, val entity: SalesCloudEntity, val localFingerprint: String = entity.fingerprint) {
    fun json() = entity.json().also { it.remove("revision") }.put("operation_id", id).put("base_revision", entity.revision)
    fun saved() = json().put("_local_fingerprint", localFingerprint)
    companion object {
        fun read(j: JSONObject): SalesCloudOperation {
            val entity = SalesCloudEntity.read(JSONObject(j.toString()).put("revision", j.getLong("base_revision")))
            return SalesCloudOperation(j.getString("operation_id"), entity, j.optString("_local_fingerprint", entity.fingerprint))
        }
    }
}

internal data class SalesCloudConflict(val id: String, val proposal: SalesCloudOperation?, val local: SalesCloudEntity, val remote: SalesCloudEntity, val choice: String = "") {
    fun json() = JSONObject().put("id", id).put("proposal", proposal?.saved() ?: JSONObject.NULL).put("local", local.json()).put("remote", remote.json()).put("choice", choice)
    companion object {
        fun read(j: JSONObject) = SalesCloudConflict(j.getString("id"), j.optJSONObject("proposal")?.let(SalesCloudOperation::read),
            SalesCloudEntity.read(j.getJSONObject("local")), SalesCloudEntity.read(j.getJSONObject("remote")), j.optString("choice"))
    }
}

internal data class SalesCloudState(
    val cursor: Long = 0, val enabled: Boolean = false, val known: Map<String, SalesCloudEntity> = emptyMap(),
    val observed: Map<String, String> = emptyMap(), val outbox: List<SalesCloudOperation> = emptyList(),
    val conflicts: List<SalesCloudConflict> = emptyList(), val lastSuccess: Long = 0, val mediaEnabled: Boolean = false,
) {
    val activeConflicts get() = conflicts.filter { it.choice.isEmpty() }
    fun json() = JSONObject().put("cursor", cursor).put("enabled", enabled).put("known", JSONArray(known.values.map { it.json() }))
        .put("observed", JSONObject(observed)).put("outbox", JSONArray(outbox.map { it.saved() }))
        .put("conflicts", JSONArray(conflicts.map { it.json() })).put("lastSuccess", lastSuccess).put("mediaEnabled", mediaEnabled)

    /** Snapshot deltas become immutable, durable operations before any HTTP request. */
    fun capture(local: Map<String, SalesCloudEntity>, newId: () -> String = { UUID.randomUUID().toString() }): SalesCloudState {
        val blocked = outbox.map { it.entity.key }.toSet() + activeConflicts.map { it.local.key }
        val observations = observed.toMutableMap()
        val operations = (local.keys + observed.keys).filterNot { it in blocked }.mapNotNull { key ->
            val current = local[key] ?: known[key]?.copy(payload = "{}", deleted = true)
                ?: return@mapNotNull null
            if (current.fingerprint == observed[key]) null
            else {
                val proposal = current.copy(revision = known[key]?.revision ?: 0,
                    payload = if (current.deleted) "{}" else salesCloudOutgoingPayload(current, known[key]))
                if (known[key]?.fingerprint == proposal.fingerprint) { observations[key] = current.fingerprint; null }
                else SalesCloudOperation(newId(), proposal, current.fingerprint)
            }
        }
        return copy(outbox = outbox + operations, observed = observations)
    }

    fun acknowledge(sent: List<SalesCloudOperation>, response: JSONObject, local: Map<String, SalesCloudEntity>): SalesCloudState {
        val results = response.getJSONArray("results").objects()
        require(results.size == sent.size && results.map { it.getString("operation_id") } == sent.map { it.id }) { "云端确认与待发送操作不匹配" }
        require(results.all { it.getString("status") in setOf("applied", "duplicate", "conflict") }) { "云端确认状态无效" }
        var next = this
        sent.zip(results).forEach { (operation, result) ->
            val key = operation.entity.key
            val revision = result.getLong("revision")
            require(revision >= 0) { "云端版本无效" }
            if (result.getString("status") == "conflict") {
                val remote = result.optJSONObject("server_entity")?.let(SalesCloudEntity::read)
                    ?: operation.entity.copy(payload = "{}", deleted = true, revision = revision)
                require(remote.key == key && remote.revision == revision) { "云端冲突与实体不匹配" }
                val current = local[key] ?: operation.entity.copy(payload = "{}", deleted = true)
                next = next.copy(known = next.known + (key to remote), outbox = next.outbox.filterNot { it.id == operation.id },
                    conflicts = next.conflicts + SalesCloudConflict(operation.id, operation, current, remote))
            } else {
                require(revision > 0) { "云端确认缺少版本" }
                val prior = next.known[key]
                next = next.copy(known = if (prior == null || revision > prior.revision) next.known + (key to operation.entity.copy(revision = revision)) else next.known,
                    observed = next.observed + (key to operation.localFingerprint), outbox = next.outbox.filterNot { it.id == operation.id })
            }
        }
        // response.cursor is deliberately ignored: another device's unseen writes may precede it.
        return next
    }

    fun pull(response: JSONObject, local: Map<String, SalesCloudEntity>, newId: () -> String = { UUID.randomUUID().toString() }): SalesCloudPull {
        val changes = response.getJSONArray("changes").objects().map(SalesCloudEntity::read)
        val cursor = response.getLong("cursor")
        require(cursor >= this.cursor && changes.zipWithNext().all { (a, b) -> a.revision < b.revision } &&
            changes.all { it.revision > this.cursor && it.revision <= cursor }) { "云端增量顺序无效" }
        require(changes.isNotEmpty() || cursor == this.cursor) { "空增量不能跳过游标" }
        var state = this
        val apply = linkedMapOf<String, SalesCloudEntity>()
        val working = local.toMutableMap()
        changes.forEach { remote ->
            val key = remote.key
            if (remote.revision <= (state.known[key]?.revision ?: 0)) return@forEach
            val pending = state.outbox.firstOrNull { it.entity.key == key }
            val conflict = state.activeConflicts.firstOrNull { it.local.key == key }
            val current = working[key] ?: remote.copy(payload = "{}", deleted = true, revision = 0)
            val sameContent = current.deleted == remote.deleted && (current.fingerprint == remote.fingerprint || (!current.deleted && salesCloudOutgoingPayload(current, remote) == remote.fingerprint))
            val localDirty = current.fingerprint != state.observed[key] && (working.containsKey(key) || state.observed.containsKey(key))
            state = state.copy(known = state.known + (key to remote))
            if (conflict != null) {
                state = state.copy(conflicts = state.conflicts.map { if (it.id == conflict.id) it.copy(remote = remote, local = current) else it })
            } else if ((pending != null || localDirty) && !sameContent) {
                state = state.copy(outbox = state.outbox.filterNot { it.entity.key == key },
                    conflicts = state.conflicts + SalesCloudConflict(newId(), pending, current, remote))
                apply.remove(key)
            } else {
                apply[key] = remote
                if (remote.deleted) working.remove(key) else working[key] = remote
                // Only final page application will persist this checkpoint with its local data.
                state = state.copy(observed = state.observed + (key to remote.fingerprint),
                    outbox = state.outbox.filterNot { it.entity.key == key && sameContent })
            }
        }
        return SalesCloudPull(state.copy(cursor = cursor), apply.values.toList(), response.getBoolean("has_more"))
    }

    companion object {
        fun read(j: JSONObject): SalesCloudState {
            val observed = j.optJSONObject("observed") ?: JSONObject()
            return SalesCloudState(j.optLong("cursor"), j.optBoolean("enabled"),
                j.optJSONArray("known")?.objects()?.map(SalesCloudEntity::read)?.associateBy { it.key }.orEmpty(),
                observed.keys().asSequence().associateWith { observed.getString(it) },
                j.optJSONArray("outbox")?.objects()?.map(SalesCloudOperation::read).orEmpty(),
                j.optJSONArray("conflicts")?.objects()?.map(SalesCloudConflict::read).orEmpty(), j.optLong("lastSuccess"), j.optBoolean("mediaEnabled"))
        }
    }
}

internal data class SalesCloudPull(val state: SalesCloudState, val apply: List<SalesCloudEntity>, val hasMore: Boolean)

internal fun salesCloudPushBody(identity: SalesCloudIdentity, operations: List<SalesCloudOperation>) = JSONObject()
    .put("space_id", identity.space).put("device_id", identity.device).put("operations", JSONArray(operations.map { it.json() }))

internal fun salesCloudBatch(identity: SalesCloudIdentity, operations: List<SalesCloudOperation>, limit: Int = 4194304): List<SalesCloudOperation> {
    val result = mutableListOf<SalesCloudOperation>()
    for (operation in operations.take(100)) {
        require(operation.entity.payload.toByteArray(Charsets.UTF_8).size <= 2097152) { "单条同步内容超过 2 MiB，待发送操作已保留；请使用附件或缩减内容" }
        if (salesCloudPushBody(identity, result + operation).toString().toByteArray(Charsets.UTF_8).size > limit) {
            require(result.isNotEmpty()) { "单条同步内容超过服务端限制，请缩减该条内容后重试；本地内容与待发送操作已保留" }
            break
        }
        result.add(operation)
    }
    return result
}
