package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Provider configuration is public; every credential value belongs exclusively in encrypted storage. */
data class FriendModelConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val providerId: String = "custom",
    val providerType: String = "api",
    val apiFormat: String = "openai_chat",
    val baseUrl: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    val credentials: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val generation: Long = 0,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "FriendModelConnection(id=$id,providerType=$providerType,apiFormat=$apiFormat,credentials=redacted)"
}

/** Pure codec: callers write config to state JSON and credentials only inside the AES/GCM envelope. */
object FriendModelConnectionCodec {
    private val formats = setOf("openai_chat", "openai_responses", "anthropic_messages", "gemini_generate")
    private val publicMetadataValues = mapOf(
        "plan_type" to setOf("agent", "coding"),
        "family" to setOf("volcengine_ark"),
        "region" to setOf("cn", "intl", "us", "global"),
        "pool_mode" to setOf("manual", "auto", "primarybackup"),
        "inference" to setOf("true", "false"),
        "quota_only" to setOf("true", "false"),
        "embedding_only" to setOf("true", "false"),
        "api_key_optional" to setOf("true", "false"),
        "endpoint_policy" to setOf("standard", "https_remote", "ark_plan"),
        "catalog_source" to setOf("desktop_preset", "user_declared", "upstream_catalog", "upstream_account", "official_plan_presets", "dynamic_account_catalog", "manual"),
        "compatibility_source" to setOf("dsh-gemini-pool/antigravity"),
    )

    fun sanitizedMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.filter { (key, value) -> value in publicMetadataValues[key].orEmpty() }

    fun validate(connection: FriendModelConnection) {
        require(connection.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "连接编号无效" }
        require(connection.name.length <= 160 && connection.providerId.length <= 128 && connection.providerType.length <= 80)
        require(connection.apiFormat in formats) { "模型协议无效" }
        require(connection.generation >= 0 && connection.baseUrl.length <= 2048)
        if (connection.baseUrl.isNotBlank()) {
            val uri = try { URI(connection.baseUrl) } catch (_: Exception) { throw IllegalArgumentException("模型地址格式无效") }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "请使用不含凭据、查询参数或片段的 HTTPS 模型地址"
            }
        }
        require(connection.model.length <= 256 && connection.models.size <= 4096 && connection.models.all { it.isNotBlank() && it.length <= 256 }) { "模型清单无效" }
        require(connection.credentials.size <= 64 && connection.credentials.all { (key, value) -> key.isNotBlank() && key.length <= 80 && value.length <= 1_048_576 }) { "连接凭据格式无效" }
    }

    fun encodeConfig(connections: List<FriendModelConnection>): JSONArray {
        require(connections.size <= 128 && connections.map { it.id }.distinct().size == connections.size) { "连接清单无效" }
        return JSONArray(connections.map { connection ->
            validate(connection)
            JSONObject().put("id", connection.id).put("name", connection.name)
                .put("providerId", connection.providerId).put("providerType", connection.providerType)
                .put("apiFormat", connection.apiFormat).put("baseUrl", connection.baseUrl)
                .put("model", connection.model).put("models", JSONArray(connection.models))
                .put("enabled", connection.enabled).put("generation", connection.generation)
                .put("metadata", JSONObject(sanitizedMetadata(connection.metadata)))
        })
    }

    fun decodeConfig(rows: JSONArray?): List<FriendModelConnection> {
        if (rows == null) return emptyList()
        require(rows.length() <= 128) { "连接数量超限" }
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val rawMetadata = row.optJSONObject("metadata")
            val metadata = rawMetadata?.keys()?.asSequence()?.mapNotNull { key ->
                (rawMetadata.opt(key) as? String)?.let { key to it }
            }?.toMap().orEmpty()
            val models = row.optJSONArray("models")?.let { values -> (0 until values.length()).map { values.getString(it) } }.orEmpty()
            FriendModelConnection(id = row.getString("id"), name = row.optString("name"),
                providerId = row.optString("providerId", "custom"), providerType = row.optString("providerType", "api"),
                apiFormat = row.optString("apiFormat", "openai_chat"), baseUrl = row.optString("baseUrl"),
                model = row.optString("model"), models = models, enabled = row.optBoolean("enabled", true),
                generation = row.optLong("generation"), metadata = sanitizedMetadata(metadata)).also(::validate)
        }.also { require(it.map { connection -> connection.id }.distinct().size == it.size) { "连接编号重复" } }
    }

    /** Binding prevents a two-file partial commit from attaching a new token to an old endpoint. */
    private fun binding(connection: FriendModelConnection): String {
        val metadata = sanitizedMetadata(connection.metadata)
        val parts = JSONArray(listOf(connection.id, connection.providerId, connection.providerType, connection.apiFormat,
            connection.baseUrl, connection.generation.toString(), metadata["family"].orEmpty(), metadata["plan_type"].orEmpty()))
        return MessageDigest.getInstance("SHA-256").digest(parts.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun encodeCredentials(connections: List<FriendModelConnection>): JSONObject = JSONObject().also { secrets ->
        require(connections.map { it.id }.distinct().size == connections.size) { "连接编号重复" }
        connections.forEach { connection ->
            validate(connection)
            secrets.put(connection.id, JSONObject().put("generation", connection.generation)
                .put("binding", binding(connection)).put("credentials", JSONObject(connection.credentials)))
        }
    }

    fun restoreCredentials(connections: List<FriendModelConnection>, secrets: JSONObject?): List<FriendModelConnection> =
        connections.map { connection ->
            val envelope = secrets?.optJSONObject(connection.id)
            if (envelope == null || envelope.optLong("generation", -1) != connection.generation || envelope.optString("binding") != binding(connection)) {
                connection.copy(credentials = emptyMap())
            } else {
                val values = envelope.optJSONObject("credentials") ?: JSONObject()
                val credentials = values.keys().asSequence().associateWith { key ->
                    values.opt(key) as? String ?: throw IllegalArgumentException("连接凭据格式无效")
                }
                connection.copy(credentials = credentials).also(::validate)
            }
        }

    fun active(connections: List<FriendModelConnection>, activeId: String): FriendModelConnection? =
        activeId.takeIf { it.isNotBlank() }?.let { id -> connections.firstOrNull { it.id == id && it.enabled } }
}

fun activeConnection(s: Store): FriendModelConnection? =
    FriendModelConnectionCodec.active(s.modelConnections, s.activeModelConnectionId)
