package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Expert(val id: String, val name: String, val prompt: String, val skills: List<String> = emptyList(), val knowledge: List<String> = emptyList())
data class Team(val id: String, val name: String, val members: List<String>)
data class Skill(val id: String, val name: String, val content: String)
data class Knowledge(val id: String, val name: String, val content: String)
/** A message-level reply target. The id is local to this device's conversation log. */
data class StarReply(val messageId: String, val role: String, val preview: String)

data class Message(
    val role: String,
    val content: String,
    /** Stable local id so a later message can point at this one. Legacy rows get an id on load. */
    val id: String = "",
    val replyToId: String = "",
    val replyToRole: String = "",
    val replyToPreview: String = "",
)

class Store(context: Context, scope: String? = null) {
    private val app = context.applicationContext
    private val root = if (scope == null) app.filesDir else File(app.filesDir, "accounts/$scope").also { require(scope.matches(Regex("[a-f0-9]{64}"))); it.mkdirs() }
    internal val workspaceRoot = File(root, "workspaces")
    internal val portableRoot = File(root, "portable")
    var portablePackages: Map<String, PortableStoredPackage> = emptyMap()
    var portableSecrets: Map<String, Map<String, String>> = emptyMap()
    var marketResources: Map<String, String> = emptyMap()
    private val state = AtomicFile(File(root, "xfriend.json"))
    // Encrypted preferences JSON lives outside Android Auto Backup, including on API 26.
    private val secrets = AtomicFile(File(app.noBackupFilesDir, if (scope == null) "xfriend-encrypted-prefs.json" else "xfriend-encrypted-prefs-$scope.json"))
    private val keyAlias = "${app.packageName}.xfriend.credentials.v1"
    var experts: List<Expert> = if (BuildConfig.SALES_APP) salesExperts else listOf(Expert("xfriend", "Xfriend 助手", "你是 Xfriend，一位认真、诚实的智能助手。默认用中文帮助用户处理任务，需要时使用工作区和知识检索工具。已有工具可以保存持久事项、文件并查询本机记录；不要笼统声称自己只读或没有跨会话记录。依据实际工具结果回答，没有证据时不要声称操作已成功。"))
    var teams: List<Team> = emptyList()
    var skills: List<Skill> = emptyList()
    var knowledge: List<Knowledge> = emptyList()
    var messages: Map<String, List<Message>> = emptyMap()
    var localRooms: List<LocalRoom> = emptyList()
    var runtimeHistorySteps: Set<String> = emptySet()
    /** Account-scoped long-term facts injected into the system prompt. */
    var memoryFacts: List<MemoryFact> = emptyList()
    var memoryTurns: Int = 0
    var memoryLastAttemptAt: Long = 0
    var memoryStatus: String = ""
    var baseUrl: String = "https://api.openai.com/v1"
    var model: String = "gpt-4o-mini"
    var modelUseTools: Boolean = true
    var conversationMode: String = ConversationMode.defaultFor(BuildConfig.SALES_APP)
    /** Persisted schema version for the one-time ask/plan -> execution migration. */
    var conversationModeVersion: Int = ConversationMode.MIGRATION_VERSION
    internal var migratedLegacyMode: Boolean = false
    var apiKey: String = ""
    /** Optional multi-provider selection; an empty selection continues using the legacy model fields. */
    var modelConnections: List<FriendModelConnection> = emptyList()
    var activeModelConnectionId: String = ""
    val activeModelConnection: FriendModelConnection? get() = activeConnection(this)
    /** Single-entry 星助理 shell. Off falls back to the classic four-tab workspace without deleting data. */
    var starUi: Boolean = true
    var asrUrl: String = ""
    var asrModel: String = ""
    var asrKey: String = ""
    /** Optional local send/reply cues. Default off; only the user changes this toggle. */
    var messageSounds: Boolean = false

    init {
        if (state.baseFile.exists() || File(state.baseFile.path + ".bak").exists()) {
            val j = JSONObject(state.openRead().bufferedReader().use { it.readText() })
            experts = j.optJSONArray("experts")?.objects()?.map { Expert(it.getString("id"), it.getString("name"), it.getString("prompt"), it.optJSONArray("skills").strings(), it.optJSONArray("knowledge").strings()) } ?: experts
            teams = j.optJSONArray("teams")?.objects()?.map { Team(it.getString("id"), it.getString("name"), it.optJSONArray("members").strings()) } ?: emptyList()
            skills = j.optJSONArray("skills")?.objects()?.map { Skill(it.getString("id"), it.getString("name"), it.getString("content")) } ?: emptyList()
            knowledge = j.optJSONArray("knowledge")?.objects()?.map { Knowledge(it.getString("id"), it.getString("name"), it.getString("content")) } ?: emptyList()
            val histories = j.optJSONObject("messages") ?: JSONObject()
            messages = histories.keys().asSequence().associateWith { id ->
                histories.getJSONArray(id).objects().mapIndexed { index, row ->
                    Message(
                        row.getString("role"), row.getString("content"),
                        row.optString("id").ifBlank { "legacy-$index" },
                        row.optString("replyToId"), row.optString("replyToRole"), row.optString("replyToPreview"),
                    )
                }
            }
            localRooms = LocalRoomCodec.decode(j.optJSONArray("localRooms"))
            runtimeHistorySteps = j.optJSONArray("runtimeHistorySteps").strings().toSet()
            memoryFacts = MemoryPolicy.decode(j.optJSONArray("memoryFacts"))
            memoryTurns = j.optInt("memoryTurns", 0)
            memoryLastAttemptAt = j.optLong("memoryLastAttemptAt", 0)
            memoryStatus = j.optString("memoryStatus", "")
            baseUrl = j.optString("baseUrl", baseUrl)
            model = j.optString("model", model)
            modelUseTools = j.optBoolean("modelUseTools", true)
            modelConnections = FriendModelConnectionCodec.decodeConfig(j.optJSONArray("modelConnections"))
            activeModelConnectionId = j.optString("activeModelConnectionId", "")
            starUi = j.optBoolean("starUi", true)
            asrUrl = j.optString("asrUrl", "")
            asrModel = j.optString("asrModel", "")
            messageSounds = j.optBoolean("messageSounds", false)
            conversationMode = j.optString("conversationMode", conversationMode).takeIf { it in ConversationMode.supported } ?: ConversationMode.defaultFor(BuildConfig.SALES_APP)
            val storedModeVersion = j.optInt("conversationModeVersion", 0)
            val migrated = ConversationMode.migrateStored(conversationMode, storedModeVersion)
            migratedLegacyMode = migrated != conversationMode || storedModeVersion < ConversationMode.MIGRATION_VERSION
            conversationMode = migrated
            conversationModeVersion = ConversationMode.MIGRATION_VERSION
            portablePackages = j.optJSONObject("portablePackages")?.let { packs -> packs.keys().asSequence().associateWith { PortableStoredPackage.read(packs.getJSONObject(it)) } }.orEmpty()
            marketResources = j.optJSONObject("marketResources")?.let { entries -> entries.keys().asSequence().associateWith { entries.getJSONObject(it).toString() } }.orEmpty()
        }
        if (secrets.baseFile.exists() || File(secrets.baseFile.path + ".bak").exists()) {
            val j = JSONObject(secrets.openRead().bufferedReader().use { it.readText() })
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(j.getString("iv"), Base64.NO_WRAP)))
            val plaintext = String(cipher.doFinal(Base64.decode(j.getString("value"), Base64.NO_WRAP)), Charsets.UTF_8)
            if (j.optInt("version", 1) == 2) {
                val decoded = JSONObject(plaintext)
                apiKey = decoded.getString("apiKey")
                asrKey = decoded.optString("asrKey", "")
                modelConnections = FriendModelConnectionCodec.restoreCredentials(modelConnections, decoded.optJSONObject("modelConnectionSecrets"))
                portableSecrets = decoded.optJSONObject("portableSecrets")?.let { packs -> packs.keys().asSequence().associateWith { id ->
                    val values = packs.getJSONObject(id); values.keys().asSequence().associateWith { values.getString(it) }
                } }.orEmpty()
            } else apiKey = plaintext
        }
    }

    @Synchronized fun save() {
        val j = JSONObject().put("experts", JSONArray(experts.map { JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt).put("skills", JSONArray(it.skills)).put("knowledge", JSONArray(it.knowledge)) }))
            .put("teams", JSONArray(teams.map { JSONObject().put("id", it.id).put("name", it.name).put("members", JSONArray(it.members)) }))
            .put("skills", JSONArray(skills.map { JSONObject().put("id", it.id).put("name", it.name).put("content", it.content) }))
            .put("knowledge", JSONArray(knowledge.map { JSONObject().put("id", it.id).put("name", it.name).put("content", it.content) }))
            .put("messages", JSONObject().also { h -> messages.forEach { (id, turns) -> h.put(id, JSONArray(turns.map {
                JSONObject().put("role", it.role).put("content", it.content).put("id", it.id)
                    .put("replyToId", it.replyToId).put("replyToRole", it.replyToRole).put("replyToPreview", it.replyToPreview)
            })) } })
            .put("localRooms", LocalRoomCodec.encode(localRooms))
            .put("runtimeHistorySteps", JSONArray(runtimeHistorySteps.toList()))
            .put("memoryFacts", MemoryPolicy.encode(memoryFacts))
            .put("memoryTurns", memoryTurns).put("memoryLastAttemptAt", memoryLastAttemptAt)
            .put("memoryStatus", memoryStatus.take(300))
            .put("baseUrl", baseUrl).put("model", model).put("modelUseTools", modelUseTools).put("conversationMode", conversationMode)
            .put("conversationModeVersion", ConversationMode.MIGRATION_VERSION)
            .put("starUi", starUi).put("asrUrl", asrUrl).put("asrModel", asrModel).put("messageSounds", messageSounds)
            .put("modelConnections", FriendModelConnectionCodec.encodeConfig(modelConnections))
            .put("activeModelConnectionId", activeModelConnectionId)
            .put("portablePackages", JSONObject().also { packs -> portablePackages.forEach { (id, pack) -> packs.put(id, pack.json()) } })
            .put("marketResources", JSONObject().also { entries -> marketResources.forEach { (id, entry) -> entries.put(id, JSONObject(entry)) } })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject().put("apiKey", apiKey).put("asrKey", asrKey).put("portableSecrets", JSONObject(portableSecrets))
            .put("modelConnectionSecrets", FriendModelConnectionCodec.encodeCredentials(modelConnections)).toString()
        val encrypted = JSONObject().put("version", 2).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("value", Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        writeAtomic(secrets, encrypted.toString())
        writeAtomic(state, j.toString())
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
internal fun writeAtomic(file: AtomicFile, text: String) {
    val stream = file.startWrite()
    try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
    catch (e: Throwable) { file.failWrite(stream); throw e }
}

