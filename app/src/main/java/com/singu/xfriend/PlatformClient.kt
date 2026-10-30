package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Direct platform protocol; no Octop server or persisted passwords. */
class PlatformClient(context: Context, accountScope: String = "legacy") {
    data class Tenant(val id: Long, val name: String)
    data class Device(val id: String, val name: String, val description: String, val isActive: String)
    data class Account(val origin: String, val loggedIn: Boolean, val name: String, val tenants: List<Tenant>)

    private val prefs = context.applicationContext.getSharedPreferences("xfriend_platform_session_v1_$accountScope", Context.MODE_PRIVATE)
    private val alias = "xfriend.platform.session.v1"
    private val mutex = Mutex()
    private val cookies = MemoryCookies()
    private val http = OkHttpClient.Builder().cookieJar(cookies)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private var origin = ""
    private var token = ""
    private var user: JSONObject? = null
    private var tenants: List<Tenant> = emptyList()
    private var loaded = false

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun save() {
        if (token.isEmpty()) {
            check(prefs.edit().clear().commit()) { "无法清除平台会话" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = JSONObject().put("origin", origin).put("token", token).toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(bytes)
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存平台会话" }
    }

    private fun load() {
        if (loaded) return
        val raw = prefs.getString("data", null)
        if (raw != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
                }
                val data = JSONObject(String(cipher.doFinal(Base64.decode(raw, Base64.NO_WRAP)), Charsets.UTF_8))
                origin = normalizeOrigin(data.getString("origin"))
                token = data.getString("token")
            } catch (_: Exception) {
                token = ""
                check(prefs.edit().clear().commit()) { "无法清除失效的平台会话" }
                throw IOException("平台会话无法解密，请重新登录")
            }
        }
        loaded = true
    }

    private fun view() = Account(origin, user != null, user?.let {
        listOf("name", "userName", "username", "id").map { field -> it.optString(field, "") }
            .firstOrNull { value -> value.isNotEmpty() && value != "null" } ?: ""
    } ?: "", tenants)

    suspend fun restore(): Account = withContext(Dispatchers.IO) { mutex.withLock {
        load()
        if (token.isNotEmpty()) user = loginInfo()
        view()
    } }

    suspend fun login(address: String, username: String, password: String): Account = withContext(Dispatchers.IO) { mutex.withLock {
        val validated = normalizeOrigin(address)
        require(username.isNotBlank() && password.isNotEmpty()) { "请输入用户名和密码" }
        loaded = true
        origin = validated
        clearSession()
        save()
        val config = request("/api/tenant/config") as? JSONObject ?: throw IOException("平台配置格式错误")
        val captcha = config.opt("openCaptcha")
        if (captcha != null && captcha != JSONObject.NULL && captcha != false && captcha.toString() !in listOf("0", "", "None", "False") &&
            config.optString("captchaSceneId", "").isNotEmpty() && config.optString("captchaPrefix", "").isNotEmpty()) {
            throw IOException("需要网页验证码验证（WEB_VERIFICATION_REQUIRED），当前原生登录不支持该验证")
        }
        val result = request("/api/user/passwordLogin", JSONObject().put("phoneOrEmail", username.trim())
            .put("areaCode", "86").put("password", password).put("captchaVerifyParam", "")) as? JSONObject
            ?: throw IOException("登录响应格式错误")
        if (result.optString("stage") == "TENANT_SELECTION_REQUIRED") {
            val rows = request("/api/me/tenants") as? JSONArray ?: throw IOException("租户响应格式错误")
            tenants = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("status") == "ACTIVE" }.map { Tenant(it.getLong("tenantId"), it.optString("name", "")) }
            if (tenants.isEmpty()) throw IOException("账号没有可用的 ACTIVE 租户")
        } else acceptToken(result)
        view()
    } }

    suspend fun selectTenant(id: Long): Account = withContext(Dispatchers.IO) { mutex.withLock {
        require(tenants.any { it.id == id }) { "无效的租户" }
        val result = request("/api/session/tenant/select", JSONObject().put("tenantId", id)) as? JSONObject
            ?: throw IOException("租户选择响应格式错误")
        acceptToken(result)
        view()
    } }

    private suspend fun acceptToken(result: JSONObject) {
        token = result.optString("token", "").takeUnless { it == "null" } ?: ""
        if (token.isEmpty()) throw IOException("需要重新登录（LOGIN_REQUIRED）")
        try {
            user = loginInfo()
            tenants = emptyList()
            save()
        } catch (e: Exception) {
            token = ""
            user = null
            throw e
        }
    }

    private suspend fun loginInfo(): JSONObject {
        val info = request("/api/user/getLoginInfo") as? JSONObject ?: throw IOException("用户响应格式错误")
        if (info.isNull("id") || info.optString("id").isEmpty() || info.optString("id") == "0") throw IOException("需要重新登录（LOGIN_REQUIRED）")
        return info
    }

    suspend fun devices(): List<Device> = withContext(Dispatchers.IO) { mutex.withLock {
        load()
        if (token.isEmpty()) throw IOException("请先登录")
        val info = loginInfo()
        val rows = request("/api/sandbox/config/list") as? JSONArray ?: throw IOException("设备响应格式错误")
        user = info
        (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("userId") == info.optString("id") }
            .map { Device(it.optString("id", ""), it.optString("name", ""), it.optString("description", ""), it.optString("isActive", "")) }
    } }

    suspend fun logout(): Account = withContext(Dispatchers.IO) { mutex.withLock {
        loaded = true
        clearSession()
        save()
        view()
    } }

    private fun clearSession() { token = ""; user = null; tenants = emptyList(); cookies.clear() }

    private suspend fun request(path: String, body: JSONObject? = null): Any? {
        val builder = Request.Builder().url(origin + path)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = http.newCall(builder.build())
        val text = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code != 200) throw IOException("平台请求失败（HTTP_${it.code}）")
                            it.body?.string() ?: throw IOException("平台响应为空")
                        }
                        continuation.resume(result)
                    } catch (e: Exception) { continuation.resumeWithException(e) }
                }
            })
        }
        val payload = JSONObject(text)
        if (payload.optString("code") != "0000") throw IOException("平台请求失败（PLATFORM_REQUEST_FAILED），请检查登录信息或网页验证要求")
        return payload.opt("data")
    }

    companion object {
        fun normalizeOrigin(raw: String): String {
            val input = raw.trim().let { if ("://" in it) it else "https://$it" }
            val uri = try { java.net.URI(input) } catch (_: Exception) { throw IllegalArgumentException("平台地址无效") }
            require(uri.scheme == "https") { "平台地址必须使用 HTTPS" }
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) { "请输入不含路径、账号、查询参数的平台源地址" }
            val url = input.toHttpUrl()
            return url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
        }
    }

    private class MemoryCookies : CookieJar {
        private val values = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) values.add(cookie)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            values.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return values.filter { it.matches(url) }
        }
        @Synchronized fun clear() = values.clear()
    }
}

