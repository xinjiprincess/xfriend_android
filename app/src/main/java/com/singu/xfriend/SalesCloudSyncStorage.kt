package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Checkpoints share the CRM Room transaction; a durable journal bridges the resource Store file. */
internal class SalesCloudSyncStorage(context: Context, val identity: SalesCloudIdentity, private val store: Store) : SalesCloudLocal {
    private val database = SalesDatabase.open(context, identity.scope)
    private val sales = SalesRepository(context, identity.scope)
    private val alias = "${context.packageName}.sales.cloudsync.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun writeRow(id: String, value: JSONObject) {
        val envelope = JSONObject().put("owner", identity.owner).put("space", identity.space).put("version", 1).put("value", value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD((identity.scope + ":" + id).toByteArray(Charsets.UTF_8)) }
        val blob = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(envelope.toString().toByteArray(Charsets.UTF_8))))
        database.entries().put(SalesEntry("cloud_sync", id, 0, blob.toString().toByteArray(Charsets.UTF_8)))
    }
    private fun readRow(id: String): JSONObject? {
        val row = database.entries().entries("cloud_sync").find { it.id == id } ?: return null
        val blob = JSONObject(String(row.payload, Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(blob.getString("iv"))))
            updateAAD((identity.scope + ":" + id).toByteArray(Charsets.UTF_8))
        }
        val envelope = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(blob.getString("data"))), Charsets.UTF_8))
        require(envelope.getString("owner") == identity.owner && envelope.getString("space") == identity.space && envelope.getInt("version") == 1) { "同步存档不属于当前账号与空间" }
        return envelope.getJSONObject("value")
    }
    private fun resources() = SalesCloudResources(store.experts, store.teams, store.skills, store.knowledge, portableSyncMetadata(store).mapValues { salesCloudCanonical(it.value) })
    private fun setResources(value: SalesCloudResources) { store.experts = value.experts; store.teams = value.teams; store.skills = value.skills; store.knowledge = value.knowledge }
    private fun snapshotUnlocked(): Map<String, SalesCloudEntity> {
        val data = sales.load()
        val base = resources()
        val metadata = base.metadata.toMutableMap()
        val media = readRow("media") ?: JSONObject()
        data.records.forEach { record ->
            val references = JSONArray()
            rawState().known["communication/${record.id}"]?.let { known ->
                JSONObject(known.payload).optJSONArray("attachments")?.let { old ->
                    for (index in 0 until old.length()) references.put(old.get(index))
                }
            }
            media.keys().forEach { id ->
                val item = media.getJSONObject(id)
                if (item.optBoolean("complete") && item.optString("local") in (record.attachments + record.file)) {
                    val reference = JSONObject(item.getJSONObject("manifest").toString())
                    reference.remove("uploaded_chunks"); reference.remove("upload_id"); reference.remove("status")
                    reference.put("primary", item.optString("local") == record.file)
                    if ((0 until references.length()).none { references.optJSONObject(it)?.optString("media_id") == id }) references.put(reference)
                }
            }
            if (references.length() > 0) metadata["communication:${record.id}"] = JSONObject().put("attachments", references).toString()
        }
        return salesCloudSnapshot(data, base.copy(metadata = metadata))
    }
    fun mediaLedger(): JSONObject = locked { readRow("media") ?: JSONObject() }
    fun saveMediaLedger(value: JSONObject) = locked { database.runInTransaction { writeRow("media", value) } }
    private fun <T> locked(action: () -> T): T = synchronized(store) { synchronized(SalesRepository.lock) { action() } }
    private fun rawState() = readRow("state")?.let(SalesCloudState::read) ?: SalesCloudState()

    override fun load(): SalesCloudState = locked { recover(); rawState() }
    fun snapshot(): Map<String, SalesCloudEntity> = locked { recover(); snapshotUnlocked() }
    fun save(state: SalesCloudState) = locked { database.runInTransaction { writeRow("state", state.json()) } }

    /** Capture edits and process an acknowledgement against one stable local snapshot. */
    override fun update(action: (SalesCloudState, Map<String, SalesCloudEntity>) -> SalesCloudState): SalesCloudState = locked {
        recover()
        action(rawState(), snapshotUnlocked()).also { next -> database.runInTransaction { writeRow("state", next.json()) } }
    }

    override fun pull(response: JSONObject): SalesCloudState = locked {
        recover()
        val local = snapshotUnlocked()
        val plan = rawState().capture(local).pull(response, local)
        apply(plan.state, plan.apply, local)
    }

    override fun resolve(conflictId: String, keepLocal: Boolean): SalesCloudState = locked {
        recover()
        val state = rawState()
        val conflict = state.activeConflicts.find { it.id == conflictId } ?: error("冲突已处理，请刷新页面")
        val local = snapshotUnlocked()
        val current = local[conflict.local.key] ?: conflict.local.copy(payload = "{}", deleted = true)
        if (!keepLocal && current.fingerprint != conflict.local.fingerprint) {
            save(state.copy(conflicts = state.conflicts.map { if (it.id == conflictId) it.copy(local = current) else it }))
            error("本地版本又有修改，已保留。请重新核对后选择")
        }
        val resolved = state.copy(conflicts = state.conflicts.map { if (it.id == conflictId) it.copy(choice = if (keepLocal) "local" else "cloud") else it })
        if (keepLocal) {
            // Resolving never reuses the rejected ID. CAS against the observed cloud version.
            val operation = SalesCloudOperation(UUID.randomUUID().toString(), current.copy(revision = conflict.remote.revision,
                payload = if (current.deleted) "{}" else salesCloudOutgoingPayload(current, conflict.remote)), current.fingerprint)
            resolved.copy(outbox = resolved.outbox.filterNot { it.entity.key == current.key } + operation).also(::save)
        } else apply(resolved, listOf(conflict.remote), local)
    }

    private fun apply(next: SalesCloudState, entities: List<SalesCloudEntity>, local: Map<String, SalesCloudEntity>): SalesCloudState {
        if (entities.isEmpty()) { save(next); return next }
        // Validate all payloads before creating a journal or changing either store.
        val targetSales = salesCloudApplySales(sales.load(), entities)
        val targetResources = salesCloudApplyResources(resources(), entities)
        val target = salesCloudSnapshot(targetSales, targetResources)
        val before = JSONObject(); val after = JSONObject()
        entities.forEach { entity -> before.put(entity.key, local[entity.key]?.fingerprint ?: "deleted"); after.put(entity.key, target[entity.key]?.fingerprint ?: "deleted") }
        val checkpoint = next.copy(observed = next.observed + entities.associate { it.key to (target[it.key]?.fingerprint ?: "deleted") })
        val journal = JSONObject().put("next", checkpoint.json()).put("entities", JSONArray(entities.map { it.json() })).put("before", before).put("after", after)
        database.runInTransaction { writeRow("journal", journal) }
        recover()
        return rawState()
    }

    /** Crash roll-forward: never advance the cursor without applying Room rows and recoverable resources. */
    private fun recover() {
        val journal = readRow("journal") ?: return
        var next = SalesCloudState.read(journal.getJSONObject("next"))
        val local = snapshotUnlocked()
        val apply = mutableListOf<SalesCloudEntity>()
        journal.getJSONArray("entities").objects().map(SalesCloudEntity::read).forEach { remote ->
            val current = local[remote.key] ?: remote.copy(payload = "{}", deleted = true)
            if (current.fingerprint == journal.getJSONObject("before").getString(remote.key) || current.fingerprint == journal.getJSONObject("after").getString(remote.key)) apply.add(remote)
            else next = next.copy(conflicts = next.conflicts + SalesCloudConflict(UUID.randomUUID().toString(), null, current, remote),
                observed = next.observed + (remote.key to (rawState().observed[remote.key] ?: "deleted")))
        }
        val previousResources = resources()
        val targetResources = salesCloudApplyResources(previousResources, apply)
        val targetSales = salesCloudApplySales(sales.load(), apply)
        try {
            database.runInTransaction {
                if (targetResources != previousResources) { setResources(targetResources); store.save() }
                sales.save(targetSales)
                writeRow("state", next.json())
                database.entries().remove("cloud_sync", "journal")
            }
        } catch (failure: Exception) { setResources(previousResources); throw failure }
    }
}
