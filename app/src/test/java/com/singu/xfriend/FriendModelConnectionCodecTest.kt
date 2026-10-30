package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendModelConnectionCodecTest {
    private fun connection(id: String = "connection-1") = FriendModelConnection(id = id, name = "个人模型服务",
        baseUrl = "https://models.example.test/v1", model = "test-model", models = listOf("test-model"), generation = 7,
        credentials = mapOf("api_key" to "api-only-in-encrypted-prefs", "access_token" to "access-only-in-encrypted-prefs",
            "refresh_token" to "refresh-only-in-encrypted-prefs", "account_id" to "account-only-in-encrypted-prefs",
            "expires_at" to "1900000123", "headers_json" to "{\"X-Provider-Secret\":\"header-only-in-encrypted-prefs\"}"))

    @Test fun configRoundTripNeverSerializesAnyCredentialValueOrCredentialObject() {
        val original = connection()
        val config = FriendModelConnectionCodec.encodeConfig(listOf(original)).toString()
        original.credentials.values.forEach { assertFalse(config.contains(it)) }
        assertFalse(config.contains("credentials")); assertFalse(config.contains("access_token")); assertFalse(config.contains("headers_json"))
        val decoded = FriendModelConnectionCodec.decodeConfig(JSONArray(config)).single()
        assertEquals(original.copy(credentials = emptyMap()), decoded)
        assertTrue(decoded.credentials.isEmpty())
        val encryptedPayload = FriendModelConnectionCodec.encodeCredentials(listOf(original))
        val restored = FriendModelConnectionCodec.restoreCredentials(listOf(decoded), JSONObject(encryptedPayload.toString())).single()
        assertEquals(original, restored)
    }

    @Test fun configCannotSmuggleCredentialsThroughDirectFieldsOrMetadata() {
        val value = connection().copy(metadata = mapOf("plan_type" to "agent", "region" to "cn",
            "api_key" to "metadata-secret", "Authorization" to "metadata-bearer", "catalog_source" to "not-a-catalog-token"))
        val config = FriendModelConnectionCodec.encodeConfig(listOf(value))
        val text = config.toString()
        assertFalse(text.contains("metadata-secret")); assertFalse(text.contains("metadata-bearer")); assertFalse(text.contains("not-a-catalog-token"))
        config.getJSONObject(0).put("credentials", JSONObject().put("api_key", "injected-unencrypted-key"))
        config.getJSONObject(0).put("api_key", "also-injected")
        val decoded = FriendModelConnectionCodec.decodeConfig(config).single()
        assertEquals(mapOf("plan_type" to "agent", "region" to "cn"), decoded.metadata)
        assertTrue(decoded.credentials.isEmpty())
    }

    @Test fun oldStateAndAnEmptySelectionKeepTheLegacyConfigurationAvailable() {
        val old = JSONObject().put("baseUrl", "https://legacy.example.test/v1").put("model", "legacy-model")
        val connections = FriendModelConnectionCodec.decodeConfig(old.optJSONArray("modelConnections"))
        assertTrue(connections.isEmpty())
        assertNull(FriendModelConnectionCodec.active(connections, old.optString("activeModelConnectionId")))
        assertEquals("https://legacy.example.test/v1", old.getString("baseUrl"))
        assertEquals("legacy-model", old.getString("model"))
        assertNull(FriendModelConnectionCodec.active(listOf(connection()), ""))
        assertNull(FriendModelConnectionCodec.active(listOf(connection()), "removed"))
        assertNull(FriendModelConnectionCodec.active(listOf(connection().copy(enabled = false)), "connection-1"))
        assertEquals(connection(), FriendModelConnectionCodec.active(listOf(connection()), "connection-1"))
    }

    @Test fun additiveConnectionSecretsDoNotChangeLegacyChatAsrOrPortableKeys() {
        val config = FriendModelConnectionCodec.decodeConfig(FriendModelConnectionCodec.encodeConfig(listOf(connection())))
        val decodedEncryptedPrefs = JSONObject().put("apiKey", "legacy-chat-key").put("asrKey", "legacy-asr-key")
            .put("portableSecrets", JSONObject().put("package", JSONObject().put("provider", "portable-key")))
            .put("modelConnectionSecrets", FriendModelConnectionCodec.encodeCredentials(listOf(connection())))
        val restored = FriendModelConnectionCodec.restoreCredentials(config, decodedEncryptedPrefs.optJSONObject("modelConnectionSecrets"))
        assertEquals(connection().credentials, restored.single().credentials)
        assertEquals("legacy-chat-key", decodedEncryptedPrefs.getString("apiKey"))
        assertEquals("legacy-asr-key", decodedEncryptedPrefs.getString("asrKey"))
        assertEquals("portable-key", decodedEncryptedPrefs.getJSONObject("portableSecrets").getJSONObject("package").getString("provider"))
        assertFalse(restored.single().credentials.values.contains("legacy-chat-key"))
    }

    @Test fun aPartialTwoFileCommitNeverAttachesANewTokenToAnOldEndpoint() {
        val old = connection()
        val updated = old.copy(baseUrl = "https://new-provider.example.test/v1", generation = old.generation + 1,
            credentials = mapOf("api_key" to "replacement-provider-key"))
        val oldConfig = FriendModelConnectionCodec.decodeConfig(FriendModelConnectionCodec.encodeConfig(listOf(old)))
        val newConfig = FriendModelConnectionCodec.decodeConfig(FriendModelConnectionCodec.encodeConfig(listOf(updated)))
        val oldSecrets = FriendModelConnectionCodec.encodeCredentials(listOf(old))
        val newSecrets = FriendModelConnectionCodec.encodeCredentials(listOf(updated))
        assertTrue(FriendModelConnectionCodec.restoreCredentials(oldConfig, newSecrets).single().credentials.isEmpty())
        assertTrue(FriendModelConnectionCodec.restoreCredentials(newConfig, oldSecrets).single().credentials.isEmpty())
        assertEquals(updated, FriendModelConnectionCodec.restoreCredentials(newConfig, newSecrets).single())
        val sameGenerationNewEndpoint = updated.copy(generation = old.generation)
        assertTrue(FriendModelConnectionCodec.restoreCredentials(oldConfig,
            FriendModelConnectionCodec.encodeCredentials(listOf(sameGenerationNewEndpoint))).single().credentials.isEmpty())
    }

    @Test fun credentialBindingAlsoGuardsWireFormatAndProviderIdentity() {
        val original = connection()
        val secrets = FriendModelConnectionCodec.encodeCredentials(listOf(original))
        listOf(original.copy(apiFormat = "anthropic_messages"), original.copy(providerType = "codex"),
            original.copy(providerId = "another-vendor"), original.copy(generation = 8)).forEach { changed ->
            assertTrue(FriendModelConnectionCodec.restoreCredentials(listOf(changed.copy(credentials = emptyMap())), secrets).single().credentials.isEmpty())
        }
    }

    @Test fun independentConnectionsRestoreOnlyTheirOwnSecretsAndRemovedIdsDoNotReappear() {
        val a = connection("one").copy(credentials = mapOf("api_key" to "provider-one-key"))
        val b = connection("two").copy(credentials = mapOf("api_key" to "provider-two-key"))
        val config = FriendModelConnectionCodec.decodeConfig(FriendModelConnectionCodec.encodeConfig(listOf(a, b)))
        val secrets = FriendModelConnectionCodec.encodeCredentials(listOf(a, b))
        val restored = FriendModelConnectionCodec.restoreCredentials(config, secrets)
        assertEquals(listOf(a, b), restored)
        assertEquals(listOf(b), FriendModelConnectionCodec.restoreCredentials(config.filter { it.id == b.id }, secrets))
        assertTrue(FriendModelConnectionCodec.restoreCredentials(config, null).all { it.credentials.isEmpty() })
    }

    @Test fun duplicateIdsAndNonStringCredentialPayloadsAreRejected() {
        val connection = connection()
        assertThrows(IllegalArgumentException::class.java) { FriendModelConnectionCodec.encodeConfig(listOf(connection, connection)) }
        assertThrows(IllegalArgumentException::class.java) { FriendModelConnectionCodec.encodeCredentials(listOf(connection, connection)) }
        val one = FriendModelConnectionCodec.encodeConfig(listOf(connection)).getJSONObject(0)
        assertThrows(IllegalArgumentException::class.java) { FriendModelConnectionCodec.decodeConfig(JSONArray().put(one).put(one)) }
        val secrets = FriendModelConnectionCodec.encodeCredentials(listOf(connection))
        secrets.getJSONObject(connection.id).getJSONObject("credentials").put("invalid", JSONObject().put("token", "nested-secret"))
        assertThrows(IllegalArgumentException::class.java) { FriendModelConnectionCodec.restoreCredentials(listOf(connection.copy(credentials = emptyMap())), secrets) }
    }

    @Test fun modelUrlsCannotCarryCredentialsOrEnablePlaintextTraffic() {
        listOf("http://localhost:11434/v1", "http://public.example.test/v1", "https://user:password@example.test/v1",
            "https://example.test/v1?api_key=hidden-url-key", "https://example.test/v1#hidden-fragment-key").forEach { url ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                FriendModelConnectionCodec.encodeConfig(listOf(connection().copy(baseUrl = url)))
            }
            assertFalse(failure.message.orEmpty().contains("hidden-url-key"))
            assertFalse(failure.message.orEmpty().contains("password"))
        }
        assertThrows(IllegalArgumentException::class.java) { FriendModelConnectionCodec.encodeConfig(listOf(connection().copy(id = "../outside"))) }
        assertThrows(IllegalArgumentException::class.java) { FriendModelConnectionCodec.encodeConfig(listOf(connection().copy(apiFormat = "unknown"))) }
    }

    @Test fun incidentalLoggingRedactsAllCredentialsAndDoesNotPrintTheProviderUrlOrName() {
        val original = connection().copy(name = "sensitive-name", baseUrl = "https://private-endpoint.example.test/v1")
        val text = original.toString()
        original.credentials.values.forEach { assertFalse(text.contains(it)) }
        assertFalse(text.contains(original.name)); assertFalse(text.contains(original.baseUrl))
        assertTrue(text.contains("credentials=redacted"))
    }
}
