package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val ACCOUNT_SERVER = "https://www.ciyuancenter.cc/xfriendmanager"
internal const val ACCOUNT_KEY = "8IPFps2DTGDTf5b14SmtK8o7wxMXbphoxw1vycyF2jk"
internal fun androidDeviceId(scopedId: String): String = MessageDigest.getInstance("SHA-256").digest(("singu-xfriend:android:" + scopedId).toByteArray()).joinToString("") { "%02x".format(it) }

internal class AccountVault(context: Context) {
    private val alias = "${context.packageName}.cloud.v1"
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud-account.bin"))
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(data.getString("iv"))))
        return JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(data.getString("data"))), Charsets.UTF_8))
    }
    fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        writeAtomic(file, JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv)).put("data", Base64.getEncoder().encodeToString(cipher.doFinal(data.toString().toByteArray()))).toString())
    }
}

internal class CloudAccount(context: Context) {
    private val mutex = Mutex()
    private val vault = AccountVault(context)
    private var saved = vault.read()
    private val device = saved.optString("device").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?: androidDeviceId(android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString())
            .also { saved.put("device", it); vault.write(saved) }
    @Volatile var session: JSONObject? = saved.optJSONObject("session")
        private set
    val identifier get() = saved.optString("identifier")
    val remember get() = saved.optBoolean("remember", true)
    val keep get() = saved.optBoolean("keep", true)
    val scope: String? get() = if (valid()) MessageDigest.getInstance("SHA-256").digest((ACCOUNT_SERVER + "\u0000" + session!!.getJSONObject("user").getString("user_id") + "\u0000" + session!!.getString("space_id")).toByteArray()).joinToString("") { "%02x".format(it) } else null
    fun valid(): Boolean = session?.let { runCatching { validateLease(it, device, System.currentTimeMillis() / 1000, saved.optLong("last_seen")) }.isSuccess } ?: false
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(12, TimeUnit.SECONDS).build()
    private suspend fun call(action: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$ACCOUNT_SERVER/api/v2/auth/$action").post(data.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            val body = response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(65537); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                buffer.copyOf(count)
            } ?: byteArrayOf()
            require(body.size <= 65536) { "账号服务响应过大" }
            val result = if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
            if (response.code == 401 || response.code == 403) {
                session = null; saved.remove("session"); vault.write(saved)
            }
            check(response.isSuccessful) { result.optString("message", "账号服务暂时不可用") }
            result
        }
    }
    private fun accept(next: JSONObject, remember: Boolean, keep: Boolean) {
        val updated = accountSessionSnapshot(saved, next, device, remember, keep, System.currentTimeMillis() / 1000)
        vault.write(updated); saved = updated; session = next
    }
    suspend fun authenticate(register: Boolean, body: JSONObject, remember: Boolean, keep: Boolean) = mutex.withLock {
        accept(call(if (register) "register" else "login", body.put("device_id", device)), remember, keep)
    }
    private suspend fun refreshLocked(spaceId: String? = null) {
        val old = session ?: error("请先登录账号")
        val body = JSONObject().put("refresh_token", old.getString("refresh_token")).put("device_id", device)
        if (spaceId != null) body.put("space_id", spaceId)
        val next = call("refresh", body)
        try {
            require(next.getJSONObject("user").getString("user_id") == old.getJSONObject("user").getString("user_id") &&
                next.getString("space_id") == (spaceId ?: old.getString("space_id"))) { "账号或空间不匹配" }
            accept(next, remember, keep)
        } catch (e: Exception) { session = null; saved.remove("session"); vault.write(saved); throw e }
    }
    suspend fun refresh() = mutex.withLock { if (session != null) refreshLocked() }

    fun syncIdentity(): SalesCloudIdentity {
        val current = session ?: error("请先登录账号")
        val currentScope = scope ?: error("账号授权已到期，请联网登录")
        return SalesCloudIdentity(currentScope, current.getJSONObject("user").getString("user_id"), current.getString("space_id"), device)
    }

    /** Space selection rotates credentials under the same lock as requests and periodic refresh. */
    suspend fun selectSpace(spaceId: String, expectedScope: String): String = mutex.withLock {
        require(scope == expectedScope) { "账号或空间已切换，请重新打开页面" }
        refreshLocked(spaceId)
        scope ?: error("所选空间不可用")
    }

    suspend fun authorizedGet(path: String, query: Map<String, String> = emptyMap(), expectedScope: String = syncIdentity().scope): JSONObject =
        request(path, "GET", query = query, expectedScope = expectedScope)

    suspend fun authorizedPost(path: String, body: JSONObject, expectedScope: String = syncIdentity().scope): JSONObject =
        request(path, "POST", body = body, expectedScope = expectedScope)

    /** Holding the session mutex through the response prevents our own refresh from revoking an in-flight token. */
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null,
        query: Map<String, String> = emptyMap(), expectedScope: String = syncIdentity().scope): JSONObject = mutex.withLock {
        require(method in listOf("GET", "POST") && path.startsWith("/api/v2/") && !path.contains("..") && !path.contains('?') && !path.contains('#')) { "无效账号服务请求" }
        require(scope == expectedScope) { "账号或空间已切换，同步已停止" }
        if (session!!.optLong("access_expires_at") <= System.currentTimeMillis() / 1000 + 30) refreshLocked()
        suspend fun send(): Pair<Int, JSONObject> = withContext(Dispatchers.IO) {
            val url = (ACCOUNT_SERVER + path).toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer " + session!!.getString("access_token"))
                .apply { if (method == "POST") post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())) else get() }.build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.byteStream()?.use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break
                        if (bytes.size() + count > 32 * 1024 * 1024) throw SalesCloudHttpException(413, "response_too_large", "云服务响应过大"); bytes.write(buffer, 0, count) }
                    String(bytes.toByteArray(), Charsets.UTF_8)
                }.orEmpty()
                val result = runCatching { if (path.startsWith("/api/v2/collaboration/") && text.trimStart().startsWith("[")) JSONObject().put("items", org.json.JSONArray(text)) else JSONObject(text) }.getOrElse {
                    throw SalesCloudHttpException(response.code, "invalid_response", "云服务返回异常响应，请稍后重试")
                }
                response.code to result
            }
        }
        var response = send()
        if (response.first == 401) { refreshLocked(); require(scope == expectedScope) { "账号或空间已切换" }; response = send() }
        if (response.first !in 200..299) throw SalesCloudHttpException(response.first,
            response.second.optString("code", "request_failed"), response.second.optString("message", "云服务请求失败，请稍后重试"))
        response.second
    }

    suspend fun authorizedMedia(path: String, method: String = "GET", bytes: ByteArray? = null,
        query: Map<String, String>, expectedScope: String): ByteArray = mutex.withLock {
        require(path.startsWith("/api/v2/sync/media/") && !path.contains("..") && !path.contains('?') && method in setOf("GET", "PUT")) { "无效媒体请求" }
        require(scope == expectedScope) { "账号或空间已切换，同步已停止" }
        require(bytes == null || bytes.size <= 262144) { "媒体分块过大" }
        if (session!!.optLong("access_expires_at") <= System.currentTimeMillis() / 1000 + 30) refreshLocked()
        suspend fun send(): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
            val url = (ACCOUNT_SERVER + path).toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
            val builder = Request.Builder().url(url).header("Authorization", "Bearer " + session!!.getString("access_token"))
            if (method == "PUT") builder.put(requireNotNull(bytes).toRequestBody("application/octet-stream".toMediaType())) else builder.get()
            http.newCall(builder.build()).execute().use { response ->
                val result = response.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val size = input.read(buffer); if (size < 0) break
                        require(output.size() + size <= 262144) { "媒体分块响应过大" }; output.write(buffer, 0, size) }
                    output.toByteArray()
                } ?: byteArrayOf()
                response.code to result
            }
        }
        var response = send()
        if (response.first == 401) { refreshLocked(); require(scope == expectedScope) { "账号或空间已切换" }; response = send() }
        if (response.first !in 200..299) {
            val error = runCatching { JSONObject(String(response.second, Charsets.UTF_8)) }.getOrDefault(JSONObject())
            throw SalesCloudHttpException(response.first, error.optString("code", "media_request_failed"), error.optString("message", "媒体传输失败，进度已保留"))
        }
        response.second
    }

    suspend fun fetch(kind: String, query: String): JSONObject {
        val collection = when (kind) { "expert", "experts" -> "experts"; "skill", "skills" -> "skills"; else -> error("不支持的市场类型") }
        return authorizedGet("/api/v2/catalog/$collection", mapOf("q" to query))
    }
    suspend fun logout() = mutex.withLock {
        val token = session?.optString("refresh_token")
        session = null; saved.remove("session"); vault.write(saved)
        if (!token.isNullOrBlank()) runCatching { call("logout", JSONObject().put("refresh_token", token)) }
    }
}
