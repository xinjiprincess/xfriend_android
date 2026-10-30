package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject

/** Interpret only the explicitly supplied credential document. No path, script or environment lookup. */
internal fun providerImportDocument(providerType: String, text: String): FriendModelConnection {
    if (text.toByteArray(Charsets.UTF_8).size > 1_048_576) providerFail("MODEL_IMPORT_SOURCE_TOO_LARGE", "账号文档不能超过 1 MiB")
    val document = runCatching { JSONObject(text.removePrefix("\uFEFF")) }.getOrElse {
        providerFail("MODEL_IMPORT_SOURCE_INVALID", "请提供有效的 JSON 账号凭据文档")
    }
    fun string(source: JSONObject, vararg keys: String): String = keys.asSequence()
        .mapNotNull { source.opt(it) as? String }.firstOrNull { it.isNotBlank() }.orEmpty()
    fun expiry(value: Double): String = (if (value > 100_000_000_000) value / 1000 else value).toLong().toString()
    return when (providerType) {
        "workbuddy_cn", "workbuddy_global" -> {
            val auth = document.optJSONObject("auth") ?: document
            val identity = document.optJSONObject("account") ?: document
            if (listOf("accessToken", "refreshToken").any { auth.opt(it) is JSONObject })
                providerFail("MODEL_DESKTOP_HELPER_REQUIRED", "此文档由桌面 WorkBuddy 加密，Android 无法解密；请在原客户端显式导出明文凭据文档后导入")
            val token = providerRequired(string(auth, "accessToken", "access_token"))
            val credentials = mapOf("access_token" to token, "refresh_token" to string(auth, "refreshToken", "refresh_token"),
                "expires_at" to expiry(auth.optDouble("expiresAt", auth.optDouble("expires_at", 0.0))),
                "domain" to string(auth, "domain"), "uid" to identity.opt("uid")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                "enterprise_id" to identity.opt("enterpriseId")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                "client_version" to string(auth, "clientVersion", "client_version").ifBlank { string(document, "clientVersion", "client_version") })
            providerNewConnection(providerType, credentials)
        }
        "codex" -> {
            val source = document.optJSONObject("tokens") ?: document
            val token = providerRequired(string(source, "access_token", "access"))
            val fromToken = providerCodexAccount(token)
            val supplied = string(source, "account_id")
            if (fromToken.isNotBlank() && supplied.isNotBlank() && supplied != fromToken)
                providerFail("MODEL_ACCOUNT_CHANGED", "文档内账号编号与访问令牌不匹配")
            val fallback = expiry(source.optDouble("expires_at", source.optDouble("expires", 0.0))).toLong()
            providerNewConnection(providerType, mapOf("access_token" to token,
                "refresh_token" to providerRequired(string(source, "refresh_token", "refresh")),
                "account_id" to providerRequired(fromToken.ifBlank { supplied }), "expires_at" to providerTokenExpiry(token, fallback).toString()))
        }
        "github_copilot" -> providerNewConnection(providerType, mapOf("github_token" to providerRequired(string(document, "github_token", "access_token")), "expires_at" to "0"))
        "xai_oauth" -> providerNewConnection(providerType, mapOf("access_token" to providerRequired(string(document, "access_token")),
            "refresh_token" to string(document, "refresh_token"), "expires_at" to expiry(document.optDouble("expires_at", 0.0))))
        "claude_subscription" -> {
            val source = document.optJSONObject("claudeAiOauth") ?: document
            providerNewConnection(providerType, mapOf("access_token" to providerRequired(string(source, "accessToken", "access_token")),
                "expires_at" to expiry(source.optDouble("expiresAt", source.optDouble("expires_at", 0.0)))))
        }
        "gemini_subscription" -> providerNewConnection(providerType, mapOf("access_token" to providerRequired(string(document, "access_token")),
            "expires_at" to expiry(document.optDouble("expiry_date", document.optDouble("expires_at", 0.0)))))
        "gemini_pool" -> {
            val raw = document.optJSONArray("accounts") ?: if (string(document, "refresh", "refresh_token").isNotBlank()) JSONArray().put(document)
                else providerFail("MODEL_IMPORT_SOURCE_INVALID", "Gemini 文档缺少 accounts 或 refresh_token")
            if (raw.length() !in 1..32) providerFail("MODEL_IMPORT_SOURCE_INVALID", "Gemini 账号池须包含 1–32 个账号")
            val accounts = (0 until raw.length()).map { index ->
                val account = raw.optJSONObject(index) ?: providerFail("MODEL_IMPORT_SOURCE_INVALID", "Gemini 账号格式无效")
                val refresh = providerRequired(string(account, "refresh", "refresh_token"))
                JSONObject().put("id", index.toString()).put("email", string(account, "email").take(254))
                    .put("access_token", string(account, "access", "access_token")).put("refresh_token", refresh)
                    .put("expires_at", expiry(account.optDouble("expires", account.optDouble("expires_at", 0.0))).toLong())
                    .put("primary", account.optBoolean("isPrimary", account.optBoolean("primary")))
                    .put("enabled", account.optString("status") != "disabled" && account.optBoolean("enabled", true))
                // Imported project IDs are deliberately ignored; loadCodeAssist must resolve account ownership.
            }
            val mode = string(document, "mode", "pool_mode", "schedulingMode").ifBlank { "manual" }.lowercase().replace("_", "").replace("-", "")
            if (mode !in setOf("manual", "auto", "primarybackup")) providerFail("MODEL_IMPORT_SOURCE_INVALID", "Gemini 账号池模式无效")
            val requestedActive = string(document, "active_account_id", "activeAccountId")
            val active = requestedActive.takeIf { selected -> accounts.any { it.optString("id") == selected } }
                ?: (0 until raw.length()).firstOrNull { raw.optJSONObject(it)?.optString("id") == requestedActive && requestedActive.isNotBlank() }?.toString()
                ?: requestedActive.ifBlank {
                accounts.firstOrNull { it.optBoolean("enabled") }?.optString("id")
                    ?: providerFail("MODEL_AUTH_REQUIRED", "账号池没有已启用账号")
            }
            if (accounts.none { it.optString("id") == active && it.optBoolean("enabled") })
                providerFail("MODEL_IMPORT_SOURCE_INVALID", "选中的 Gemini 账号不存在或已停用")
            providerNewConnection(providerType, mapOf("accounts_json" to JSONArray(accounts).toString(), "pool_mode" to mode, "active_account_id" to active),
                mapOf("pool_mode" to mode, "compatibility_source" to "dsh-gemini-pool/antigravity"))
        }
        else -> providerFail("MODEL_PROTOCOL_UNSUPPORTED", "该类型不支持订阅凭据文档导入")
    }
}
