package com.singu.xfriend

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.util.Base64

internal class AccountAuthorizationException(val code: String, message: String) : IllegalArgumentException(message)

private fun requireAuthorization(condition: Boolean, code: String, message: String) {
    if (!condition) throw AccountAuthorizationException(code, message)
}

/** The issuance skew matches the desktop client; every expiry boundary remains strict. */
internal fun validateLease(session: JSONObject, device: String, now: Long, lastSeen: Long, publicKey: String = ACCOUNT_KEY): JSONObject {
    val lease = runCatching {
        val signed = session.getJSONObject("lease")
        val bytes = Base64.getUrlDecoder().decode(signed.getString("payload"))
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Base64.getUrlDecoder().decode(publicKey), 0))
            update(bytes, 0, bytes.size)
        }
        require(verifier.verifySignature(Base64.getUrlDecoder().decode(signed.getString("signature"))))
        JSONObject(String(bytes, Charsets.UTF_8))
    }.getOrElse { throw AccountAuthorizationException("invalid_signature", "授权签名无效，请联网重新登录") }
    val identityMatches = runCatching {
        lease.getInt("schema_version") == 2 && lease.getString("product") == "singu-xfriend" &&
            lease.getString("device_id") == device && lease.getString("user_id") == session.getJSONObject("user").getString("user_id") &&
            lease.getString("space_id") == session.getString("space_id") &&
            lease.getString("user_id").isNotBlank() && lease.getString("space_id").isNotBlank()
    }.getOrDefault(false)
    requireAuthorization(identityMatches, "identity_mismatch", "授权与当前账号、空间或设备不匹配，请重新登录")
    val times = runCatching {
        listOf(lease.getLong("not_before"), lease.getLong("issued_at"), lease.getLong("offline_until"),
            lease.getLong("expires_at"), session.getLong("session_expires_at"))
    }.getOrElse { throw AccountAuthorizationException("invalid_lease", "账号服务返回的授权期限无效，请联网重新登录") }
    val (notBefore, issuedAt, offlineUntil, expiresAt, sessionExpiresAt) = times
    requireAuthorization(notBefore <= now + 300 && issuedAt <= now + 300 && now + 300 >= lastSeen,
        "clock_mismatch", "手机时间与授权时间不一致，请在系统设置中同步日期和时间后重试")
    requireAuthorization(offlineUntil <= issuedAt + 72 * 3600 && offlineUntil <= expiresAt,
        "invalid_offline_period", "离线授权有效期无效，请联网重新登录")
    requireAuthorization(now < expiresAt, "subscription_expired", "当前空间的使用授权已到期，请联系管理员检查该账号的授权")
    requireAuthorization(now < sessionExpiresAt, "session_expired", "登录会话已到期，请联网重新登录；账号激活状态由服务端核验")
    requireAuthorization(now < offlineUntil, "offline_expired", "离线授权已到期，请联网刷新登录；这不代表账号激活已到期")
    return lease
}

/** Only a newly verified online lease may replace an old clock watermark. Never trust server_time for this. */
internal fun accountSessionSnapshot(saved: JSONObject, next: JSONObject, device: String, remember: Boolean, keep: Boolean,
    now: Long, publicKey: String = ACCOUNT_KEY): JSONObject {
    val lease = validateLease(next, device, now, 0, publicKey)
    return JSONObject(saved.toString()).put("remember", remember).put("keep", keep)
        .put("identifier", if (remember) next.getJSONObject("user").optString("identifier") else "")
        .put("last_seen", lease.getLong("issued_at"))
        .also { if (keep) it.put("session", next) else it.remove("session") }
}
