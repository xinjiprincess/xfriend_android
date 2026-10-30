package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException

class FriendProvidersUiTest {
    @Test fun blankKeyEditRetainsSavedCredentialsAndReplacementDoesNotEraseAccountFields() {
        val saved = mapOf("api_key" to "saved-api-key", "refresh_token" to "saved-refresh", "account_id" to "account")
        assertEquals(saved, friendProviderEditedCredentials(saved, "  "))
        assertEquals(mapOf("api_key" to "replacement", "refresh_token" to "saved-refresh", "account_id" to "account"),
            friendProviderEditedCredentials(saved, " replacement "))
        assertEquals("saved-api-key", saved.getValue("api_key"))
    }

    @Test fun deviceVerificationOpensOnlyTheExactHttpsHostForItsProvider() {
        assertEquals("https://auth.openai.com/codex/device", friendProviderVerificationUrl("codex", "https://auth.openai.com/codex/device"))
        assertEquals("https://github.com/login/device", friendProviderVerificationUrl("github_copilot", "https://github.com/login/device"))
        assertEquals("https://auth.x.ai/device", friendProviderVerificationUrl("xai_oauth", "https://auth.x.ai/device"))
        listOf("http://github.com/login/device", "https://github.com.attacker.test/login/device",
            "https://github.com@attacker.test/", "https://account@github.com/", "https://github.com:8443/", "https://github.com/#redirect")
            .forEach { assertNull(friendProviderVerificationUrl("github_copilot", it)) }
        assertNull(friendProviderVerificationUrl("codex", "https://github.com/login/device"))
        assertNull(friendProviderVerificationUrl("custom", "https://github.com/login/device"))
    }

    @Test fun documentReadAllowsOneMegabyteAndRejectsTheNextByte() {
        val maximum = ByteArray(1_048_576) { 'a'.code.toByte() }
        assertEquals(1_048_576, friendReadProviderDocument(ByteArrayInputStream(maximum)).length)
        assertThrows(IllegalArgumentException::class.java) {
            friendReadProviderDocument(ByteArrayInputStream(maximum + byteArrayOf(1)))
        }
    }

    @Test fun malformedUtf8IsRejectedRatherThanSilentlyChangingCredentialValues() {
        assertThrows(CharacterCodingException::class.java) {
            friendReadProviderDocument(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertEquals("{\"name\":\"本人账号\"}", friendReadProviderDocument(ByteArrayInputStream("{\"name\":\"本人账号\"}".toByteArray())))
    }
}
