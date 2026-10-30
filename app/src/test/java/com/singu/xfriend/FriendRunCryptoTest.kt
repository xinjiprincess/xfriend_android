package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class FriendRunCryptoTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun encryptedUserContentRoundTripsAndUsesFreshIv() {
        val key = key(); val crypto = FriendRunCrypto({ key }, "app:scope")
        val a = crypto.encrypt("私密输入 partial"); val b = crypto.encrypt("私密输入 partial")
        assertFalse(a.contains("私密输入")); assertNotEquals(a, b)
        assertEquals("私密输入 partial", crypto.decrypt(a))
    }
    @Test fun wrongScopeOrApplicationCannotReadCiphertext() {
        val key = key(); val encrypted = FriendRunCrypto({ key }, "app:a").encrypt("secret")
        assertThrows(AEADBadTagException::class.java) { FriendRunCrypto({ key }, "app:b").decrypt(encrypted) }
        assertThrows(AEADBadTagException::class.java) { FriendRunCrypto({ key }, "other-app:a").decrypt(encrypted) }
    }
    @Test fun replacedKeyAndTamperingFailClosed() {
        val key = key(); val crypto = FriendRunCrypto({ key }, "a"); val encrypted = crypto.encrypt("secret")
        val other = key()
        assertThrows(AEADBadTagException::class.java) { FriendRunCrypto({ other }, "a").decrypt(encrypted) }
        val tampered = JSONObject(encrypted); val bytes = java.util.Base64.getDecoder().decode(tampered.getString("ciphertext"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte(); tampered.put("ciphertext", java.util.Base64.getEncoder().encodeToString(bytes))
        assertThrows(AEADBadTagException::class.java) { crypto.decrypt(tampered.toString()) }
    }
}
