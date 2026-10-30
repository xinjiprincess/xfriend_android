package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*

internal data class FriendRemoteSaved(val session: FriendRemoteSession? = null, val run: FriendRemoteRun? = null, val history: List<FriendRemoteRun> = emptyList())
internal interface FriendRemoteStorage {
    fun read(): FriendRemoteSaved
    fun write(value: FriendRemoteSaved)
    fun updateForSession(expectedSession: FriendRemoteSession, transform: (FriendRemoteSaved) -> FriendRemoteSaved): FriendRemoteSaved? = synchronized(this) {
        val current = read()
        if (current.session != expectedSession) null else transform(current).also(::write)
    }
}

internal class FriendRemoteVault(context: Context, accountScope: String) : FriendRemoteStorage {
    init { require(accountScope.isNotBlank()) { "请先登录账号" } }
    private val scope = scopeKey(accountScope)
    private val generation = FriendRemoteSessions.generation(scope)
    private val alias = "${context.packageName}.friend.remote.$scope"
    // Context.noBackupFilesDir may create the directory. Resolve it only in IO read/write/clear.
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir, "friend-remote-$scope.bin")) }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): FriendRemoteSaved = FriendRemoteSessions.access(scope, generation) { readRaw() }
    private fun readRaw(): FriendRemoteSaved {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return FriendRemoteSaved()
        val j = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(j.getString("iv"))))
            updateAAD(scope.toByteArray())
        }
        val value = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(j.getString("data"))), Charsets.UTF_8))
        require(value.getString("scope") == scope)
        val session = value.optJSONObject("session")?.let { FriendRemoteSession(companionOrigin(it.getString("origin")), it.getString("cookie")) }
        val history = value.optJSONArray("history")
        return FriendRemoteSaved(session, value.optJSONObject("run")?.let(FriendRemoteRun::parse),
            if (history == null) emptyList() else (0 until history.length()).map { FriendRemoteRun.parse(history.getJSONObject(it)) })
    }
    override fun write(value: FriendRemoteSaved) = FriendRemoteSessions.access(scope, generation) { writeRaw(value) }
    override fun updateForSession(expectedSession: FriendRemoteSession, transform: (FriendRemoteSaved) -> FriendRemoteSaved): FriendRemoteSaved? = FriendRemoteSessions.access(scope, generation) {
        val current = readRaw()
        if (current.session != expectedSession) null else transform(current).also(::writeRaw)
    }
    fun updateObserved(run: FriendRemoteRun, expectedSession: FriendRemoteSession): Boolean {
        if (!run.belongsTo(expectedSession)) return false
        return updateForSession(expectedSession) { it.withRun(run, select = false) } != null
    }
    private fun writeRaw(value: FriendRemoteSaved) {
        val payload = JSONObject().put("scope", scope)
        value.session?.let { payload.put("session", JSONObject().put("origin", it.origin).put("cookie", it.cookie)) }
        value.run?.let { payload.put("run", it.json()) }
        payload.put("history", org.json.JSONArray().also { list -> value.history.forEach { list.put(it.json()) } })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(scope.toByteArray()) }
        val bytes = cipher.doFinal(payload.toString().toByteArray())
        val encoded = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv)).put("data", Base64.getEncoder().encodeToString(bytes)).toString()
        val output = file.startWrite()
        try { output.write(encoded.toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun scopeKey(scope: String) = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        fun registerObservation(accountScope: String, job: Job) = FriendRemoteSessions.register(scopeKey(accountScope), job)
        /** Revokes observers synchronously; disk cleanup and bounded network logout run on IO. */
        fun clearSession(context: Context, accountScope: String): Job {
            val key = scopeKey(accountScope)
            val generation = FriendRemoteSessions.invalidate(key)
            val app = context.applicationContext
            return cleanupScope.launch {
                val vault = FriendRemoteVault(app, accountScope)
                val old = FriendRemoteSessions.cleanup(key, generation) {
                    val session = runCatching { vault.readRaw().session }.getOrNull()
                    // Clear disk before network: offline logout must never retain a reusable cookie.
                    vault.file.delete()
                    check(listOf("", ".bak", ".new").none { File(vault.file.baseFile.path + it).exists() }) { "Remote session cleanup failed" }
                    session
                }
                // New login need not wait for the old computer. Old generations remain invalid.
                if (old != null) runCatching { FriendRemoteClient().logout(old) }
            }
        }
    }
}
