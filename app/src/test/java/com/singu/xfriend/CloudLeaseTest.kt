package com.singu.xfriend

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class CloudLeaseTest {
    @Test fun deviceIdMatchesManagerContract() { val id = androidDeviceId("android-scoped-test-id"); assertTrue(id.matches(Regex("[a-f0-9]{64}"))); assertEquals(id, androidDeviceId("android-scoped-test-id")); assertNotEquals(id, androidDeviceId("another-device")) }
    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKey.generatePublicKey().encoded)
    private val now = 1_790_000_000L
    private fun session(change: (JSONObject) -> Unit = {}): JSONObject {
        val lease = JSONObject().put("schema_version", 2).put("product", "singu-xfriend").put("user_id", "user-a")
            .put("space_id", "space-a").put("device_id", "device-a").put("issued_at", now).put("not_before", now)
            .put("expires_at", now + 300_000).put("offline_until", now + 72 * 3600)
        change(lease)
        val bytes = lease.toString().toByteArray()
        val signer = Ed25519Signer().apply { init(true, privateKey); update(bytes, 0, bytes.size) }
        fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return JSONObject().put("lease", JSONObject().put("payload", encode(bytes)).put("signature", encode(signer.generateSignature())))
            .put("user", JSONObject().put("user_id", "user-a")).put("space_id", "space-a").put("session_expires_at", now + 300_000)
    }
    @Test fun validSignedDesktopLease() { assertEquals("user-a", validateLease(session(), "device-a", now, now, publicKey).getString("user_id")) }
    @Test fun rejectWrongDevice() { assertThrows(IllegalArgumentException::class.java) { validateLease(session(), "other", now, now, publicKey) } }
    @Test fun rejectUserSwap() { assertThrows(IllegalArgumentException::class.java) { validateLease(session { it.put("user_id", "other") }, "device-a", now, now, publicKey) } }
    @Test fun rejectSpaceSwap() { assertThrows(IllegalArgumentException::class.java) { validateLease(session { it.put("space_id", "other") }, "device-a", now, now, publicKey) } }
    @Test fun rejectLongerThan72Hours() { assertThrows(IllegalArgumentException::class.java) { validateLease(session { it.put("offline_until", now + 72 * 3600 + 1) }, "device-a", now, now, publicKey) } }
    @Test fun rejectExpiry() { assertThrows(IllegalArgumentException::class.java) { validateLease(session(), "device-a", now + 72 * 3600, now, publicKey) } }
    @Test fun rejectClockRollback() { assertThrows(IllegalArgumentException::class.java) { validateLease(session(), "device-a", now, now + 301, publicKey) } }
    @Test fun rejectTamperedPayload() {
        val data = session(); val signed = data.getJSONObject("lease")
        signed.put("payload", Base64.getUrlEncoder().encodeToString("{}".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { validateLease(data, "device-a", now, now, publicKey) }
    }
    @Test fun rejectOtherProduct() { assertThrows(IllegalArgumentException::class.java) { validateLease(session { it.put("product", "other") }, "device-a", now, now, publicKey) } }

    @Test fun freshlyIssuedLeaseAllowsTheSameClockSkewAsDesktop() {
        listOf(0L, 2L, 300L).forEach { behind ->
            assertEquals("user-a", validateLease(session(), "device-a", now - behind, now, publicKey).getString("user_id"))
        }
        val failure = assertThrows(AccountAuthorizationException::class.java) {
            validateLease(session(), "device-a", now - 301, now, publicKey)
        }
        assertEquals("clock_mismatch", failure.code)
        assertTrue(failure.message!!.contains("手机时间"))
    }

    @Test fun issuanceToleranceDoesNotExtendAnyExpirationBoundary() {
        val offline = assertThrows(AccountAuthorizationException::class.java) {
            validateLease(session(), "device-a", now + 72 * 3600, now, publicKey)
        }
        assertEquals("offline_expired", offline.code)
        val subscription = assertThrows(AccountAuthorizationException::class.java) {
            validateLease(session(), "device-a", now + 300_000, now, publicKey)
        }
        assertEquals("subscription_expired", subscription.code)
        val expiredSession = session().put("session_expires_at", now)
        val login = assertThrows(AccountAuthorizationException::class.java) {
            validateLease(expiredSession, "device-a", now, now, publicKey)
        }
        assertEquals("session_expired", login.code)
    }

    @Test fun verifiedOnlineLoginRecoversOldClockWatermarkWithoutChangingTheSavedInput() {
        val saved = JSONObject().put("last_seen", now + 86400).put("device", "device-a")
        val next = session().put("server_time", now + 999999)
        val updated = accountSessionSnapshot(saved, next, "device-a", true, true, now, publicKey)

        assertEquals(now, updated.getLong("last_seen"))
        assertEquals(now + 86400, saved.getLong("last_seen"))
        assertEquals("device-a", updated.getString("device"))
        assertEquals(next.toString(), updated.getJSONObject("session").toString())
        validateLease(updated.getJSONObject("session"), "device-a", now, updated.getLong("last_seen"), publicKey)
        val rollback = assertThrows(AccountAuthorizationException::class.java) {
            validateLease(next, "device-a", now, saved.getLong("last_seen"), publicKey)
        }
        assertEquals("clock_mismatch", rollback.code)
    }

    @Test fun invalidOnlineLeaseNeverResetsWatermarkOrReplacesSavedSession() {
        val saved = JSONObject().put("last_seen", now + 86400).put("session", JSONObject().put("marker", "keep"))
        val original = saved.toString()
        val tampered = session()
        tampered.getJSONObject("lease").put("signature", Base64.getUrlEncoder().encodeToString(ByteArray(64)))
        val signature = assertThrows(AccountAuthorizationException::class.java) {
            accountSessionSnapshot(saved, tampered, "device-a", true, true, now, publicKey)
        }
        assertEquals("invalid_signature", signature.code)
        val binding = assertThrows(AccountAuthorizationException::class.java) {
            accountSessionSnapshot(saved, session(), "other-device", true, true, now, publicKey)
        }
        assertEquals("identity_mismatch", binding.code)
        assertEquals(original, saved.toString())
    }

    @Test fun onlineRecoveryCannotBypassExpiredAuthorizationOrClockValidation() {
        val saved = JSONObject().put("last_seen", now + 86400)
        listOf(
            now - 301 to "clock_mismatch",
            now + 72 * 3600 to "offline_expired",
            now + 300_000 to "subscription_expired",
        ).forEach { (time, code) ->
            val failure = assertThrows(AccountAuthorizationException::class.java) {
                accountSessionSnapshot(saved, session(), "device-a", true, true, time, publicKey)
            }
            assertEquals(code, failure.code)
            assertEquals(now + 86400, saved.getLong("last_seen"))
        }
    }

    @Test fun onlineAcceptanceStillHonorsRememberAndKeepLoginPreferences() {
        val saved = JSONObject().put("identifier", "old").put("session", JSONObject().put("marker", "old"))
        val next = session().also { it.getJSONObject("user").put("identifier", "current") }
        val persistent = accountSessionSnapshot(saved, next, "device-a", true, true, now, publicKey)
        assertEquals("current", persistent.getString("identifier"))
        assertTrue(persistent.getBoolean("remember"))
        assertTrue(persistent.getBoolean("keep"))
        val ephemeral = accountSessionSnapshot(saved, next, "device-a", false, false, now, publicKey)
        assertEquals("", ephemeral.getString("identifier"))
        assertFalse(ephemeral.has("session"))
        assertFalse(ephemeral.getBoolean("remember"))
        assertFalse(ephemeral.getBoolean("keep"))
    }
}
