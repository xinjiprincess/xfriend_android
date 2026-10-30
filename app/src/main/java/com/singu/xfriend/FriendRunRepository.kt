package com.singu.xfriend

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Blocking encrypted I/O: construct and call on Dispatchers.IO, never the UI thread. */
class FriendRunRepository(context: Context, val scope: String) {
    private val queue: FriendRunQueue
    private val identity: String
    init {
        FriendRunIds.scope(scope)
        check(Looper.myLooper() != Looper.getMainLooper()) { "任务存储必须在 IO 线程访问" }
        val app = context.applicationContext
        identity = "${app.packageName}:friend-runs:$scope"
        val file = AtomicFile(File(app.noBackupFilesDir, "friend-runs-$scope.json"))
        val crypto = FriendRunCrypto({ key("${app.packageName}.friend.runs.$scope.v1") }, identity)
        val storage = object : FriendRunStorage {
            override fun read(): List<FriendRun> = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                FriendRunCodec.decode(crypto.decrypt(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })) else emptyList()
            override fun write(runs: List<FriendRun>) {
                val bytes = crypto.encrypt(FriendRunCodec.encode(runs)).toByteArray(Charsets.UTF_8)
                val output = file.startWrite()
                try { output.write(bytes); file.finishWrite(output) }
                catch (e: Throwable) { file.failWrite(output); throw e }
            }
        }
        synchronized(locks) {
            val lock = locks.getOrPut(identity) { Any() }
            queue = FriendRunQueue(storage, lock)
            // Once per process, NOT once per Repository instance (VM and Service both open it).
            if (identity !in recovered) { queue.recoverStale(scope); recovered.add(identity) }
        }
    }
    fun runs(): List<FriendRun> = queue.runs()
    fun recoverInterrupted() = queue.recoverStale(scope)
    fun enqueue(resourceId: String, input: String, team: Boolean, clientRequestId: String, conversationMode: String = ConversationMode.CRAFT, localRoomTurn: LocalRoomTurn? = null, reply: StarReply? = null,
        requestMessageId: String = "", historySnapshot: List<Message>? = null): FriendRun =
        queue.enqueue(resourceId, input, team, clientRequestId, conversationMode = conversationMode, localRoomTurn = localRoomTurn, reply = reply,
            requestMessageId = requestMessageId, historySnapshot = historySnapshot)
    /** Explicit new attempt. Original partial/result remains immutable; this is not a checkpoint resume. */
    fun retry(id: String, clientRequestId: String = FriendRunIds.newId(), requestMessageId: String = "", historySnapshot: List<Message>? = null): FriendRun {
        FriendRunIds.run(id)
        val old = runs().firstOrNull { it.id == id } ?: error("找不到原任务")
        return queue.enqueue(old.resourceId, old.input, old.team, clientRequestId, old.id, old.conversationMode, old.localRoomTurn,
            old.replyToId.takeIf { it.isNotBlank() }?.let { StarReply(it, old.replyToRole, old.replyPreview) },
            requestMessageId = requestMessageId, historySnapshot = historySnapshot)
    }
    fun spawnChild(parentRunId: String, resourceId: String, input: String, clientRequestId: String,
        title: String = "", maxModelCalls: Int = 8, tokenBudget: Long = 32_000, timeoutMillis: Long = 180_000): FriendRun =
        queue.spawnChild(parentRunId, resourceId, input, clientRequestId, title, maxModelCalls, tokenBudget, timeoutMillis)
    fun followUpAgent(id: String, input: String, clientRequestId: String): FriendRun = queue.followUpAgent(id, input, clientRequestId)
    fun admitModelRequest(id: String, requestId: String, reservedTokens: Long): FriendRun = queue.admitModelRequest(id, requestId, reservedTokens)
    fun saveAgentContext(id: String, messages: Map<String, List<Message>>, historySteps: Set<String>): FriendRun =
        queue.saveAgentContext(id, messages, historySteps)
    fun saveRootContext(id: String, messages: Map<String, List<Message>>, historySteps: Set<String>): FriendRun =
        queue.saveRootContext(id, messages, historySteps)
    fun startFailed(id: String) { queue.startFailed(id) }
    fun cancel(id: String) = queue.cancel(id)
    /** User action only. Does not kick the Service or silently replay uncertain tools. */
    fun resume(id: String): FriendRun = queue.resume(id)
    /** Explicit '核查并继续': runtime must verify existing write by hash; never auto-rewrite. */
    fun verifyAndResume(id: String): FriendRun = queue.verifyAndResume(id)
    fun restart(id: String, clientRequestId: String = FriendRunIds.newId()): FriendRun = retry(id, clientRequestId)
    internal fun claim(): FriendRun? = queue.claim()
    internal fun claimConcurrent(): FriendRun? = queue.claimConcurrent()
    internal fun cancelAll() = queue.cancelAll()
    internal fun ledger(): FriendRunQueue = queue
    companion object {
        private val locks = mutableMapOf<String, Any>()
        private val recovered = mutableSetOf<String>()
        private fun key(alias: String): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
