package com.singu.xfriend

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AAD binds each encrypted file to its application/account space. Wrong keys fail closed. */
internal class FriendRunCrypto(private val key: () -> SecretKey, private val identity: String) {
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return JSONObject().put("version", 1).put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))).toString()
    }
    fun decrypt(envelope: String): String {
        val json = JSONObject(envelope); require(json.getInt("version") == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(json.getString("iv"))))
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.getDecoder().decode(json.getString("ciphertext"))), Charsets.UTF_8)
    }
}
