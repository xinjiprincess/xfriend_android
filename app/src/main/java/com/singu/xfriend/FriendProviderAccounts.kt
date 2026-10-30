package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FriendProviderFailure(val code: String, val userMessage: String) : Exception(userMessage)

/** Short-lived device-flow material stays in memory and is never a saved connection. */
class ProviderDeviceLogin internal constructor(
    val providerType: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAt: Long,
    intervalSeconds: Int,
    private val parameters: Map<String, String>,
) {
    var intervalSeconds: Int = intervalSeconds
        internal set
    private val cancelled = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    internal val mutex = Mutex()
    internal var nextPollAt = 0L
    fun cancel() { cancelled.set(true) }
    internal fun active(now: Long): Boolean = !cancelled.get() && !completed.get() && now < expiresAt
    internal fun parameter(name: String): String = parameters[name].orEmpty()
    internal fun complete() { completed.set(true) }
    override fun toString(): String = "ProviderDeviceLogin(providerType=$providerType,credentials=redacted)"
}

sealed interface ProviderLoginResult {
    data class Pending(val intervalSeconds: Int = 5, val slowDown: Boolean = false) : ProviderLoginResult
    data class Complete(val connection: FriendModelConnection) : ProviderLoginResult
    data class Failed(val code: String, val message: String) : ProviderLoginResult
}

internal const val PROVIDER_CODEX_CLIENT = "app_EMoamEEZ73f0CkXaXp7hrann"
internal const val PROVIDER_CODEX_TOKEN = "https://auth.openai.com/oauth/token"
internal const val PROVIDER_CODEX_DEVICE = "https://auth.openai.com/api/accounts/deviceauth/usercode"
internal const val PROVIDER_CODEX_POLL = "https://auth.openai.com/api/accounts/deviceauth/token"
internal const val PROVIDER_GITHUB_CLIENT = "Iv1.b507a08c87ecfe98"
internal const val PROVIDER_XAI_CLIENT = "b1a00492-073a-47ea-816f-4c329264a828"
internal const val PROVIDER_XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
internal const val PROVIDER_POOL_BASE = "https://cloudcode-pa.googleapis.com"
internal val providerCopilotHeaders = mapOf(
    "Editor-Version" to "vscode/1.110.1", "Editor-Plugin-Version" to "copilot-chat/0.38.2",
    "User-Agent" to "GitHubCopilotChat/0.38.2", "Copilot-Integration-Id" to "vscode-chat",
    "X-GitHub-Api-Version" to "2025-10-01",
)

internal fun providerFail(code: String, message: String): Nothing = throw FriendProviderFailure(code, message)
internal fun providerRequired(value: String): String = value.takeIf { it.isNotBlank() }
    ?: providerFail("MODEL_AUTH_REQUIRED", "该连接需要重新授权，请重新登录或导入有效凭据")
internal fun providerSafeHeaders(headers: Map<String, String>): Map<String, String> {
    if (headers.any { (name, value) -> !name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) || value.length > 16384 || value.any { it.code !in 32..126 } })
        providerFail("MODEL_CONFIGURATION_INVALID", "账号凭据或请求头格式无效，请重新授权或导入")
    return headers
}

internal fun providerBase(type: String): String = when (type) {
    "codex" -> "https://chatgpt.com/backend-api/codex"
    "workbuddy_cn" -> "https://copilot.tencent.com/v2"
    "workbuddy_global" -> "https://www.workbuddy.ai/v2"
    "github_copilot" -> "https://api.githubcopilot.com"
    "xai_oauth" -> "https://api.x.ai/v1"
    "claude_subscription" -> "https://api.anthropic.com"
    "gemini_subscription", "gemini_pool" -> PROVIDER_POOL_BASE
    else -> providerFail("MODEL_PROTOCOL_UNSUPPORTED", "该服务不支持订阅账号授权")
}

internal fun providerSafeEndpoint(raw: String, host: (String) -> Boolean): String {
    val url = runCatching { raw.toHttpUrl() }.getOrNull()
        ?: providerFail("MODEL_AUTH_RESPONSE_INVALID", "授权服务返回了无效地址")
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.fragment != null || !host(url.host)) providerFail("MODEL_AUTH_RESPONSE_INVALID", "授权服务返回了不可信地址")
    return url.toString().trimEnd('/')
}

internal fun providerCodexAccount(token: String): String = runCatching {
    val payload = token.split('.')[1]
    JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
        .optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id").orEmpty()
}.getOrDefault("")

internal fun providerTokenExpiry(token: String, fallback: Long): Long = runCatching {
    JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8)).optLong("exp", fallback)
}.getOrDefault(fallback)

internal fun providerNewConnection(type: String, credentials: Map<String, String>, metadata: Map<String, String> = emptyMap()): FriendModelConnection {
    val id = UUID.randomUUID().toString()
    val name = when (type) {
        "codex" -> "Codex / ChatGPT"; "github_copilot" -> "GitHub Copilot"; "xai_oauth" -> "xAI / Grok"
        "workbuddy_cn" -> "WorkBuddy 国内"; "workbuddy_global" -> "WorkBuddy 国际"
        "gemini_pool" -> "Gemini / Antigravity"; "claude_subscription" -> "Claude 订阅额度"
        "gemini_subscription" -> "Gemini 订阅额度"; else -> type
    }
    return FriendModelConnection(id = id, name = name, providerId = "native-$id", providerType = type,
        apiFormat = when (type) { "codex", "xai_oauth" -> "openai_responses"; "gemini_pool" -> "gemini_generate"; else -> "openai_chat" },
        baseUrl = providerBase(type), model = "", models = emptyList(), credentials = credentials,
        enabled = true, generation = 1, metadata = metadata + mapOf("inference" to (type !in setOf("claude_subscription", "gemini_subscription")).toString(), "quota_only" to (type in setOf("claude_subscription", "gemini_subscription")).toString()))
}

/** No browser launch, background polling, credential file scanning, logging or persistence. */
class FriendProviderAccounts(
    client: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private data class HttpResult(val status: Int, val document: JSONObject)

    private suspend fun raw(method: String, url: String, headers: Map<String, String> = emptyMap(),
        json: JSONObject? = null, form: Map<String, String>? = null): HttpResult {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull()
            ?: providerFail("MODEL_CONFIGURATION_INVALID", "账号服务地址无效")
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null)
            providerFail("MODEL_CONFIGURATION_INVALID", "账号服务地址必须为 HTTPS")
        val body = form?.let { values -> FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build() }
            ?: (json ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(parsed).header("Accept", "application/json")
            .apply { providerSafeHeaders(headers).forEach { (k, v) -> header(k, v) }; if (method == "GET") get() else post(body) }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(FriendProviderFailure("MODEL_NETWORK_ERROR", "无法连接账号服务，请检查网络后重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val bytes = it.body?.byteStream()?.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    if (output.size() + count > 2 * 1024 * 1024) providerFail("MODEL_UPSTREAM_INVALID", "账号服务响应过大")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: byteArrayOf()
                            val document = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { _ ->
                                if (it.isSuccessful) providerFail("MODEL_UPSTREAM_INVALID", "账号服务响应格式无效") else JSONObject()
                            }
                            HttpResult(it.code, document)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            error as? FriendProviderFailure ?: FriendProviderFailure("MODEL_UPSTREAM_INVALID", "账号服务响应无效"))
                    }
                }
            })
        }
    }

    private suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(),
        json: JSONObject? = null, form: Map<String, String>? = null): JSONObject {
        val result = raw(method, url, headers, json, form)
        if (result.status !in 200..299) httpFailure(result.status)
        if (result.document.has("code") && !result.document.isNull("code") && result.document.optInt("code", -1) != 0)
            providerFail("MODEL_REQUEST_REJECTED", "账号服务拒绝了请求，请检查授权状态")
        if (result.document.has("error") && !result.document.isNull("error"))
            providerFail("MODEL_REQUEST_REJECTED", "账号服务拒绝了请求，请检查授权状态")
        return result.document
    }

    private fun httpFailure(status: Int): Nothing = when (status) {
        401, 403 -> providerFail("MODEL_AUTH_REQUIRED", "账号授权失效或权限不足，请重新授权")
        429 -> providerFail("MODEL_RATE_LIMITED", "账号服务请求过于频繁，请稍后重试")
        in 300..399 -> providerFail("MODEL_REDIRECT_REJECTED", "账号服务返回了重定向，已停止传递凭据")
        else -> providerFail("MODEL_UPSTREAM_ERROR", "账号服务暂时不可用（HTTP $status）")
    }

    private suspend fun xaiEndpoints(): Map<String, String> {
        val doc = request("GET", "https://auth.x.ai/.well-known/openid-configuration")
        if (doc.optString("issuer") != "https://auth.x.ai") providerFail("MODEL_AUTH_RESPONSE_INVALID", "xAI 授权签发方不匹配")
        return listOf("token_endpoint", "device_authorization_endpoint").associateWith { name ->
            providerSafeEndpoint(doc.optString(name)) { it == "auth.x.ai" }
        }
    }

    suspend fun startLogin(providerType: String): ProviderDeviceLogin {
        if (providerType == "codex") {
            val doc = request("POST", PROVIDER_CODEX_DEVICE, json = JSONObject().put("client_id", PROVIDER_CODEX_CLIENT))
            val device = providerRequired(doc.optString("device_auth_id"))
            val user = providerRequired(doc.optString("user_code", doc.optString("usercode")))
            return ProviderDeviceLogin(providerType, user, "https://auth.openai.com/codex/device", now() + 900,
                doc.optInt("interval", 5).coerceIn(5, 60), mapOf("device" to device, "token_endpoint" to PROVIDER_CODEX_POLL))
        }
        val endpoint: String
        val token: String
        val clientId: String
        val scope: String
        when (providerType) {
            "github_copilot" -> {
                endpoint = "https://github.com/login/device/code"; token = "https://github.com/login/oauth/access_token"
                clientId = PROVIDER_GITHUB_CLIENT; scope = "read:user"
            }
            "xai_oauth" -> {
                val endpoints = xaiEndpoints(); endpoint = endpoints.getValue("device_authorization_endpoint")
                token = endpoints.getValue("token_endpoint"); clientId = PROVIDER_XAI_CLIENT; scope = PROVIDER_XAI_SCOPE
            }
            else -> providerFail("MODEL_PROTOCOL_UNSUPPORTED", "该账号类型需要显式导入凭据文档")
        }
        val doc = request("POST", endpoint, form = mapOf("client_id" to clientId, "scope" to scope))
        val verification = providerSafeEndpoint(providerRequired(doc.optString("verification_uri"))) { host ->
            if (providerType == "github_copilot") host == "github.com" else host == "auth.x.ai"
        }
        return ProviderDeviceLogin(providerType, providerRequired(doc.optString("user_code")), verification,
            now() + doc.optLong("expires_in", 900).coerceIn(1, 86400), doc.optInt("interval", 5).coerceIn(5, 60),
            mapOf("device" to providerRequired(doc.optString("device_code")), "token_endpoint" to token, "client_id" to clientId))
    }

    suspend fun pollLogin(login: ProviderDeviceLogin): ProviderLoginResult = login.mutex.withLock {
        if (!login.active(now())) return@withLock ProviderLoginResult.Failed("MODEL_OPERATION_CANCELLED", "授权已取消、已完成或已过期，请重新开始")
        if (now() < login.nextPollAt) return@withLock ProviderLoginResult.Pending(login.intervalSeconds)
        login.nextPollAt = now() + login.intervalSeconds
        try {
            val response = if (login.providerType == "codex") raw("POST", PROVIDER_CODEX_POLL,
                json = JSONObject().put("device_auth_id", login.parameter("device")).put("user_code", login.userCode))
            else raw("POST", login.parameter("token_endpoint"), form = mapOf("client_id" to login.parameter("client_id"),
                "device_code" to login.parameter("device"), "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            if (!login.active(now())) return@withLock ProviderLoginResult.Failed("MODEL_OPERATION_CANCELLED", "授权已取消或已过期")
            val doc = response.document
            if (login.providerType == "codex" && response.status in setOf(403, 404)) return@withLock ProviderLoginResult.Pending(login.intervalSeconds)
            when (doc.optString("error")) {
                "authorization_pending" -> return@withLock ProviderLoginResult.Pending(login.intervalSeconds)
                "slow_down" -> {
                    login.intervalSeconds = (login.intervalSeconds + 5).coerceAtMost(60)
                    login.nextPollAt = now() + login.intervalSeconds
                    return@withLock ProviderLoginResult.Pending(login.intervalSeconds, true)
                }
                "" -> Unit
                else -> { login.cancel(); return@withLock ProviderLoginResult.Failed("MODEL_AUTH_FAILED", "账号授权未完成或已被拒绝，请重新开始") }
            }
            if (response.status !in 200..299) httpFailure(response.status)
            val credentials = when (login.providerType) {
                "codex" -> {
                    val code = doc.optString("authorization_code"); val verifier = doc.optString("code_verifier")
                    if (code.isBlank() || verifier.isBlank()) return@withLock ProviderLoginResult.Pending(login.intervalSeconds)
                    codexCredentials(request("POST", PROVIDER_CODEX_TOKEN, form = mapOf("grant_type" to "authorization_code",
                        "client_id" to PROVIDER_CODEX_CLIENT, "code" to code, "code_verifier" to verifier,
                        "redirect_uri" to "https://auth.openai.com/deviceauth/callback")))
                }
                "github_copilot" -> copilotExchange(providerRequired(doc.optString("access_token")))
                else -> oauthCredentials(doc)
            }
            currentCoroutineContext().ensureActive()
            if (!login.active(now())) return@withLock ProviderLoginResult.Failed("MODEL_OPERATION_CANCELLED", "授权已取消或已过期，凭据未保存")
            val connection = providerNewConnection(login.providerType, credentials)
            login.complete()
            ProviderLoginResult.Complete(connection)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: FriendProviderFailure) { ProviderLoginResult.Failed(error.code, error.userMessage) }
        catch (_: Exception) { ProviderLoginResult.Failed("MODEL_AUTH_RESPONSE_INVALID", "授权服务返回了无效响应，请重新开始") }
    }

    private fun oauthCredentials(doc: JSONObject, previous: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "access_token" to providerRequired(doc.optString("access_token")),
        "refresh_token" to doc.optString("refresh_token").ifBlank { previous["refresh_token"].orEmpty() },
        "expires_at" to (now() + doc.optLong("expires_in", 3600).coerceAtLeast(1)).toString(),
    )

    private fun codexCredentials(doc: JSONObject, previous: Map<String, String> = emptyMap()): Map<String, String> {
        val credentials = oauthCredentials(doc, previous)
        val account = providerRequired(providerCodexAccount(credentials.getValue("access_token")))
        if (previous["account_id"].orEmpty().isNotBlank() && account != previous["account_id"])
            providerFail("MODEL_ACCOUNT_CHANGED", "刷新后的账号与原连接不同，请重新授权")
        providerRequired(credentials["refresh_token"].orEmpty())
        return credentials + ("account_id" to account)
    }

    private suspend fun copilotExchange(githubToken: String): Map<String, String> {
        val doc = request("GET", "https://api.github.com/copilot_internal/v2/token",
            providerCopilotHeaders + ("Authorization" to "token ${providerRequired(githubToken)}"))
        val endpoint = providerSafeEndpoint(doc.optJSONObject("endpoints")?.optString("api", "https://api.githubcopilot.com")
            ?: "https://api.githubcopilot.com") { it == "githubcopilot.com" || it.endsWith(".githubcopilot.com") }
        return mapOf("access_token" to providerRequired(doc.optString("token")), "github_token" to githubToken,
            "expires_at" to doc.optLong("expires_at").toString(), "api_endpoint" to endpoint)
    }

    suspend fun refresh(connection: FriendModelConnection): FriendModelConnection {
        if (!connection.enabled) providerFail("MODEL_CONNECTION_DISABLED", "该连接已停用")
        if (connection.providerType in setOf("api", "volcengine_ark")) return connection
        if (connection.providerType == "gemini_pool") return refreshPool(connection)
        val old = connection.credentials
        if (old["expires_at"]?.toDoubleOrNull()?.toLong()?.let { it > now() + 120 } == true) return connection
        val updated = when (connection.providerType) {
            "github_copilot" -> copilotExchange(providerRequired(old["github_token"].orEmpty()))
            "codex" -> codexCredentials(request("POST", PROVIDER_CODEX_TOKEN, form = mapOf("grant_type" to "refresh_token",
                "refresh_token" to providerRequired(old["refresh_token"].orEmpty()), "client_id" to PROVIDER_CODEX_CLIENT)), old)
            "xai_oauth" -> oauthCredentials(request("POST", xaiEndpoints().getValue("token_endpoint"), form = mapOf(
                "grant_type" to "refresh_token", "refresh_token" to providerRequired(old["refresh_token"].orEmpty()),
                "client_id" to PROVIDER_XAI_CLIENT, "scope" to PROVIDER_XAI_SCOPE)), old)
            "workbuddy_cn", "workbuddy_global" -> {
                val headers = mutableMapOf("X-Refresh-Token" to providerRequired(old["refresh_token"].orEmpty()),
                    "X-Auth-Refresh-Source" to "workbuddy", "User-Agent" to "CLI/2.63.2 CodeBuddy/2.63.2")
                old["enterprise_id"]?.takeIf { it.isNotBlank() }?.let { headers["X-Enterprise-Id"] = it }
                val doc = request("POST", providerBase(connection.providerType) + "/plugin/auth/token/refresh", headers).optJSONObject("data")
                    ?: providerFail("MODEL_AUTH_RESPONSE_INVALID", "刷新授权响应无效")
                mapOf("access_token" to providerRequired(doc.optString("accessToken")),
                    "refresh_token" to doc.optString("refreshToken").ifBlank { old["refresh_token"].orEmpty() },
                    "expires_at" to (now() + doc.optLong("expiresIn", 3600)).toString())
            }
            else -> providerFail("MODEL_AUTH_REQUIRED", "该订阅凭据需要在原客户端重新授权后导入")
        }
        currentCoroutineContext().ensureActive()
        return connection.copy(credentials = old + updated)
    }

    suspend fun fetchModels(connection: FriendModelConnection): List<String> {
        if (connection.providerType in setOf("claude_subscription", "gemini_subscription"))
            providerFail("MODEL_SUBSCRIPTION_QUOTA_ONLY", "该订阅目前仅支持查询额度，不能用于聊天")
        val c = connection
        if (c.providerType == "gemini_pool") return providerPoolAccounts(c).filter { it.optBoolean("enabled", true) }
            .filter { c.credentials["pool_mode"] != "manual" || it.optString("id") == c.credentials["active_account_id"] }
            .flatMap { account -> poolModelIds(request("POST", "$PROVIDER_POOL_BASE/v1internal:fetchAvailableModels",
                providerPoolHeaders(providerRequired(account.optString("access_token"))), JSONObject().put("project", account.optString("project_id")))) }.distinct().sorted()
            .also { if (it.isEmpty()) providerFail("MODEL_CATALOG_EMPTY", "该账号没有返回可用模型，请检查套餐与权限") }
        val token = providerRequired(c.credentials["access_token"] ?: c.credentials["api_key"].orEmpty())
        val headers = mutableMapOf("Authorization" to "Bearer $token")
        val document = when (c.providerType) {
            "codex" -> { headers["chatgpt-account-id"] = providerRequired(c.credentials["account_id"].orEmpty()); request("GET", "${providerBase("codex")}/models?client_version=0.153.4", headers) }
            "workbuddy_cn", "workbuddy_global" -> request("GET", providerBase(c.providerType).removeSuffix("/v2") + "/v3/config",
                providerWorkbuddyHeaders(c, headers) + ("User-Agent" to if (c.providerType == "workbuddy_cn") "CLI/2.63.2 CodeBuddy/2.63.2" else "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2"))
            "github_copilot" -> request("GET", providerCopilotEndpoint(c) + "/models", headers + providerCopilotHeaders)
            else -> {
                val base = if (c.providerType == "xai_oauth") providerBase(c.providerType) else c.baseUrl.trimEnd('/')
                val auth = when (c.apiFormat) {
                    "anthropic_messages" -> mapOf("x-api-key" to token, "anthropic-version" to "2023-06-01")
                    "gemini_generate" -> mapOf("x-goog-api-key" to token)
                    else -> headers
                }
                request("GET", "$base/models", auth)
            }
        }
        val models = when (c.providerType) {
            "codex" -> document.optJSONArray("models").providerObjects().filter { it.optString("visibility") == "list" }.map { it.optString("slug") }
            "workbuddy_cn", "workbuddy_global" -> {
                val doc = document.optJSONObject("data") ?: document
                val roster = doc.optJSONArray("agents").providerObjects().firstOrNull { it.optString("name") == "cli" }?.optJSONArray("models")
                    ?: providerFail("MODEL_CATALOG_INVALID", "WorkBuddy 未返回 CLI 模型列表")
                val allowed = (0 until roster.length()).map { roster.optString(it) }.toSet()
                doc.optJSONArray("models").providerObjects().filter { it.optString("id") in allowed && !it.optBoolean("disabled") }.map { it.optString("id") }
            }
            "github_copilot" -> document.optJSONArray("data").providerObjects().filter {
                it.opt("model_picker_enabled") != false && it.optJSONArray("supported_endpoints")?.let { a ->
                    (0 until a.length()).any { i -> a.optString(i) == if (c.apiFormat == "openai_responses") "/responses" else "/chat/completions" }
                } == true
            }.map { it.optString("id") }
            else -> (document.optJSONArray(if (c.apiFormat == "gemini_generate") "models" else "data")).providerObjects()
                .map { it.optString(if (c.apiFormat == "gemini_generate") "name" else "id").removePrefix("models/") }
        }.filter { it.isNotBlank() }.distinct()
        if (models.isEmpty()) providerFail("MODEL_CATALOG_EMPTY", "账号服务没有返回可用模型，请手动核对模型权限")
        return models
    }

    suspend fun fetchQuota(connection: FriendModelConnection): JSONObject {
        if (connection.providerType in setOf("api", "volcengine_ark", "xai_oauth")) return JSONObject().put("supported", false).put("status", "unknown")
        val c = connection
        if (c.providerType == "gemini_pool") return poolQuota(c)
        val headers = mutableMapOf("Authorization" to "Bearer ${providerRequired(c.credentials["access_token"].orEmpty())}")
        val payload = when (c.providerType) {
            "codex" -> {
                headers["chatgpt-account-id"] = providerRequired(c.credentials["account_id"].orEmpty())
                val doc = request("GET", "https://chatgpt.com/backend-api/wham/usage", headers)
                JSONObject().apply { listOf("plan_type", "rate_limit", "code_review_rate_limit", "credits", "additional_rate_limits").forEach { if (doc.has(it)) put(it, doc.get(it)) } }
            }
            "github_copilot" -> request("GET", "https://api.github.com/copilot_internal/user", providerCopilotHeaders + ("Authorization" to "token ${providerRequired(c.credentials["github_token"].orEmpty())}"))
            "claude_subscription" -> request("GET", "https://api.anthropic.com/api/oauth/usage", headers + ("anthropic-beta" to "oauth-2025-04-20"))
            "gemini_subscription" -> {
                val loaded = request("POST", "$PROVIDER_POOL_BASE/v1internal:loadCodeAssist", headers,
                    JSONObject().put("metadata", JSONObject().put("ideType", "IDE_UNSPECIFIED").put("platform", "PLATFORM_UNSPECIFIED").put("pluginType", "GEMINI")))
                val project = loaded.optJSONObject("cloudaicompanionProject")?.optString("id") ?: (loaded.opt("cloudaicompanionProject") as? String).orEmpty()
                request("POST", "$PROVIDER_POOL_BASE/v1internal:retrieveUserQuota", headers, JSONObject().apply { if (project.isNotBlank()) put("project", project) })
            }
            "workbuddy_cn", "workbuddy_global" -> {
                val auth = providerWorkbuddyHeaders(c, headers)
                val enterprise = c.credentials["enterprise_id"].orEmpty().isNotBlank() && c.providerType == "workbuddy_cn"
                val root = if (c.providerType == "workbuddy_global") "https://www.workbuddy.ai" else "https://www.codebuddy.cn"
                val doc = request("POST", "$root/v2/billing/meter/${if (enterprise) "get-enterprise-user-usage" else "get-user-resource"}", auth,
                    if (enterprise) JSONObject() else JSONObject().put("PageNumber", 1).put("PageSize", 100).put("ProductCode", "p_tcaca").put("Status", JSONArray(listOf(0, 3))))
                doc.optJSONObject("data") ?: JSONObject()
            }
            else -> providerFail("MODEL_PROTOCOL_UNSUPPORTED", "该连接暂不支持额度查询")
        }
        return JSONObject().put("supported", true).put("checked_at", now()).put("source", "upstream_account").put("data", payload)
    }

    fun importDocument(providerType: String, text: String): FriendModelConnection = providerImportDocument(providerType, text)

    private suspend fun refreshPool(connection: FriendModelConnection): FriendModelConnection {
        val accounts = providerPoolAccounts(connection)
        var changed = false
        for (account in accounts.filter { it.optBoolean("enabled", true) }) {
            if (account.optString("access_token").isBlank() || account.optLong("expires_at") <= now() + 120) {
                // Installed-app identifiers from the pinned public compatibility implementation, not user secrets.
                val data = request("POST", "https://oauth2.googleapis.com/token", form = mapOf("grant_type" to "refresh_token",
                    "refresh_token" to providerRequired(account.optString("refresh_token")), "client_id" to providerPoolClientId,
                    "client_secret" to providerPoolClientSecret))
                account.put("access_token", providerRequired(data.optString("access_token")))
                    .put("refresh_token", data.optString("refresh_token").ifBlank { account.optString("refresh_token") })
                    .put("expires_at", now() + data.optLong("expires_in", 3600))
                changed = true
            }
            if (account.optString("project_id").isBlank()) {
                val loaded = request("POST", "$PROVIDER_POOL_BASE/v1internal:loadCodeAssist", providerPoolHeaders(account.optString("access_token")),
                    JSONObject().put("metadata", JSONObject().put("ideType", "ANTIGRAVITY").put("platform", "PLATFORM_UNSPECIFIED").put("pluginType", "GEMINI")))
                val project = loaded.optJSONObject("cloudaicompanionProject")?.optString("id") ?: (loaded.opt("cloudaicompanionProject") as? String).orEmpty()
                if (project.isBlank()) providerFail("MODEL_ACCOUNT_FORBIDDEN", "该 Gemini 账号没有返回可用 CloudCode 项目")
                account.put("project_id", project); changed = true
            }
            if (!account.has("model_ids")) {
                val available = request("POST", "$PROVIDER_POOL_BASE/v1internal:fetchAvailableModels", providerPoolHeaders(account.optString("access_token")),
                    JSONObject().put("project", account.optString("project_id")))
                val ids = poolModelIds(available)
                val quota = JSONObject()
                ids.forEach { model ->
                    val value = available.optJSONObject("models")?.optJSONObject(model)?.optJSONObject("quotaInfo")?.opt("remainingFraction") as? Number
                    value?.toDouble()?.takeIf { it in 0.0..1.0 }?.let { quota.put(model, it) }
                }
                account.put("model_ids", JSONArray(ids)).put("model_quota", quota)
                changed = true
            }
        }
        return if (!changed) connection else connection.copy(credentials = connection.credentials + ("accounts_json" to JSONArray(accounts).toString()))
    }

    private suspend fun poolQuota(connection: FriendModelConnection): JSONObject {
        val result = JSONArray()
        for (account in providerPoolAccounts(connection).filter { it.optBoolean("enabled", true) }) {
            val auth = providerPoolHeaders(account.optString("access_token"))
            val summary = request("POST", "$PROVIDER_POOL_BASE/v1internal:retrieveUserQuotaSummary", auth, JSONObject())
            val available = request("POST", "$PROVIDER_POOL_BASE/v1internal:fetchAvailableModels", auth, JSONObject().put("project", account.optString("project_id")))
            val models = available.optJSONObject("models") ?: providerFail("MODEL_UPSTREAM_INVALID", "Gemini 额度响应缺少模型信息")
            val buckets = JSONArray()
            for (model in poolModelIds(available)) {
                val quota = models.optJSONObject(model)?.optJSONObject("quotaInfo") ?: JSONObject()
                val fraction = (quota.opt("remainingFraction") as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 }
                buckets.put(JSONObject().put("modelId", model).put("remainingFraction", fraction ?: JSONObject.NULL).put("resetTime", quota.opt("resetTime") ?: JSONObject.NULL))
            }
            result.put(JSONObject().put("account_id", account.optString("id")).put("buckets", buckets)
                .put("groups", summary.optJSONArray("groups") ?: JSONArray()))
        }
        return JSONObject().put("supported", true).put("checked_at", now()).put("source", "antigravity_account")
            .put("data", JSONObject().put("accounts", result))
    }
}

internal fun JSONArray?.providerObjects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun providerPoolAccounts(connection: FriendModelConnection): List<JSONObject> = runCatching {
    JSONArray(connection.credentials["accounts_json"] ?: "[]").providerObjects()
}.getOrElse { providerFail("MODEL_IMPORT_SOURCE_INVALID", "Gemini 账号池格式无效，请重新导入") }
internal fun poolModelIds(document: JSONObject): List<String> {
    val models = document.optJSONObject("models") ?: providerFail("MODEL_UPSTREAM_INVALID", "Gemini 模型列表格式无效")
    return models.keys().asSequence().filter { !it.startsWith("chat_") && !it.startsWith("tab_") && models.optJSONObject(it)?.optBoolean("isInternal") == false }.toList()
}
internal val providerPoolClientId = BuildConfig.PROVIDER_POOL_CLIENT_ID
internal val providerPoolClientSecret = BuildConfig.PROVIDER_POOL_CLIENT_SECRET
internal fun providerPoolHeaders(token: String): Map<String, String> = mapOf("Authorization" to "Bearer ${providerRequired(token)}",
    "User-Agent" to "antigravity/1.15.8", "X-Goog-Api-Client" to "google-cloud-sdk vscode_cloudshelleditor/0.1",
    "Client-Metadata" to JSONObject().put("ideType", "ANTIGRAVITY").put("platform", "PLATFORM_UNSPECIFIED").put("pluginType", "GEMINI").toString())
internal fun providerCopilotEndpoint(c: FriendModelConnection): String = providerSafeEndpoint(c.credentials["api_endpoint"] ?: providerBase("github_copilot")) {
    it == "githubcopilot.com" || it.endsWith(".githubcopilot.com")
}
internal fun providerWorkbuddyHeaders(c: FriendModelConnection, original: Map<String, String> = emptyMap()): Map<String, String> {
    val global = c.providerType == "workbuddy_global"
    val version = c.credentials["client_version"].orEmpty().ifBlank { if (global) "5.5.2" else "5.5.6" }
    val root = if (global) "https://www.workbuddy.ai" else "https://www.codebuddy.cn"
    return original + mapOf("X-IDE-Type" to "WorkBuddy", "X-IDE-Name" to "WorkBuddy", "X-Product" to "SaaS",
        "X-IDE-Version" to version, "User-Agent" to "WorkBuddy/$version ${if (global) "WorkBuddy AI" else "WorkBuddy"}/$version",
        "Origin" to root, "Referer" to "$root/", "X-Requested-With" to "XMLHttpRequest") + buildMap {
        listOf(Triple("uid", "X-User-Id", "X-No-User-Id"), Triple("enterprise_id", "X-Enterprise-Id", "X-No-Enterprise-Id"),
            Triple("domain", "X-Domain", "X-No-Department-Info")).forEach { (field, header, absent) ->
            val value = c.credentials[field].orEmpty(); put(if (value.isNotBlank()) header else absent, value.ifBlank { "1" })
        }
        c.credentials["enterprise_id"]?.takeIf { it.isNotBlank() }?.let { put("X-Tenant-Id", it) }
    }
}
