package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendProviderImportsTest {
    @Test fun plaintextWorkbuddyImportSelectsKnownCredentialsAndIgnoresScriptsAndPaths() {
        val doc = JSONObject().put("auth", JSONObject().put("accessToken", "private-access").put("refreshToken", "private-refresh")
            .put("expiresAt", 1_800_000_000_000L).put("domain", "company").put("clientVersion", "5.5.6"))
            .put("account", JSONObject().put("uid", 42).put("enterpriseId", "enterprise"))
            .put("command", "never execute").put("path", "/private/auth.json")
        val connection = providerImportDocument("workbuddy_cn", doc.toString())
        assertEquals("private-access", connection.credentials["access_token"])
        assertEquals("1800000000", connection.credentials["expires_at"])
        assertEquals("42", connection.credentials["uid"])
        assertEquals("enterprise", connection.credentials["enterprise_id"])
        assertFalse(connection.credentials.toString().contains("never execute"))
        assertFalse(FriendModelConnectionCodec.encodeConfig(listOf(connection)).toString().contains("private-access"))
    }

    @Test fun desktopEncryptedWorkbuddyDocumentsRequireAnExplicitExternalUnlock() {
        val doc = JSONObject().put("auth", JSONObject().put("accessToken", JSONObject().put("\$wbEncrypted", 1).put("envelope", "private")))
        try { providerImportDocument("workbuddy_cn", doc.toString()); fail() }
        catch (failure: FriendProviderFailure) { assertEquals("MODEL_DESKTOP_HELPER_REQUIRED", failure.code) }
    }

    @Test fun quotaOnlySubscriptionsStayUnableToGenerateEvenWithValidImportedTokens() = runBlocking {
        listOf("claude_subscription" to JSONObject().put("claudeAiOauth", JSONObject().put("accessToken", "claude-private").put("expiresAt", 1_800_000_000_000L)),
            "gemini_subscription" to JSONObject().put("access_token", "gemini-private").put("expiry_date", 1_800_000_000_000L)).forEach { (type, doc) ->
            val connection = providerImportDocument(type, doc.toString())
            assertEquals("false", connection.metadata["inference"])
            assertEquals("true", connection.metadata["quota_only"])
            try { FriendProviderAccounts().fetchModels(connection); fail() }
            catch (failure: FriendProviderFailure) { assertEquals("MODEL_SUBSCRIPTION_QUOTA_ONLY", failure.code) }
            try { FriendProviderAccountTransport.prepare(connection, JSONObject(), "session"); fail() }
            catch (failure: FriendProviderFailure) { assertEquals("MODEL_SUBSCRIPTION_QUOTA_ONLY", failure.code) }
        }
    }

    @Test fun geminiPoolPreservesExplicitModeAndAccountWhileIgnoringUntrustedImportedProjects() {
        val doc = JSONObject().put("mode", "primarybackup").put("active_account_id", "1").put("accounts", JSONArray()
            .put(JSONObject().put("access", "first-access").put("refresh", "first-refresh").put("projectId", "untrusted-project").put("isPrimary", true))
            .put(JSONObject().put("access", "second-access").put("refresh", "second-refresh")))
        val connection = providerImportDocument("gemini_pool", doc.toString())
        assertEquals("primarybackup", connection.credentials["pool_mode"])
        assertEquals("1", connection.credentials["active_account_id"])
        val accounts = providerPoolAccounts(connection)
        assertEquals(2, accounts.size)
        assertTrue(accounts.none { it.has("project_id") || it.has("projectId") })
        assertFalse(FriendModelConnectionCodec.encodeConfig(listOf(connection)).toString().contains("first-refresh"))
        assertFalse(FriendModelConnectionCodec.encodeConfig(listOf(connection)).toString().contains("second-access"))
    }

    @Test fun malformedOrAmbiguousPoolSelectionIsRejectedWithoutBorrowingOtherCredentials() {
        val doc = JSONObject().put("active_account_id", "missing").put("accounts", JSONArray()
            .put(JSONObject().put("refresh", "one")))
        try { providerImportDocument("gemini_pool", doc.toString()); fail() }
        catch (failure: FriendProviderFailure) { assertEquals("MODEL_IMPORT_SOURCE_INVALID", failure.code) }
        try { providerImportDocument("workbuddy_global", "{bad json"); fail() }
        catch (failure: FriendProviderFailure) { assertEquals("MODEL_IMPORT_SOURCE_INVALID", failure.code) }
    }

    @Test fun externalPoolAccountIdsAndLegacyModeSpellingResolveToTheSameSelectedAccount() {
        val doc = JSONObject().put("schedulingMode", "primaryBackup").put("activeAccountId", "external-second")
            .put("accounts", JSONArray().put(JSONObject().put("id", "external-first").put("refresh", "first"))
                .put(JSONObject().put("id", "external-second").put("refresh", "second")))
        val connection = providerImportDocument("gemini_pool", doc.toString())
        assertEquals("primarybackup", connection.credentials["pool_mode"])
        assertEquals("1", connection.credentials["active_account_id"])
        assertEquals("second", providerPoolAccounts(connection).first { it.getString("id") == connection.credentials["active_account_id"] }.getString("refresh_token"))
    }
}
