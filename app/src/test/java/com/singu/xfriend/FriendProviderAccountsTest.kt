package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Real request/response codecs with an injected transport: no external account or socket is used. */
class FriendProviderAccountsTest {
    private class Http(vararg replies: Pair<Int, JSONObject>) : Interceptor {
        val requests = mutableListOf<Request>()
        private val replies = ArrayDeque(replies.toList())
        var beforeReply: ((Request) -> Unit)? = null
        val client = OkHttpClient.Builder().addInterceptor(this).build()
        override fun intercept(chain: Interceptor.Chain): Response = synchronized(this) {
            val request = chain.request()
            requests += request
            beforeReply?.invoke(request)
            check(replies.isNotEmpty()) { "Unexpected test request" }
            val (status, body) = replies.removeFirst()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toString().toResponseBody()).build()
        }
    }

    private fun jwt(account: String): String {
        val payload = JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account)).put("exp", 9000)
        return "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())}.signature"
    }
    private fun code() = JSONObject().put("device_auth_id", "private-device-code").put("user_code", "ABCD-EFGH").put("interval", 5)
    private fun token(account: String = "account-a") = JSONObject().put("access_token", jwt(account)).put("refresh_token", "private-refresh").put("expires_in", 3600)

    @Test fun codexDeviceFlowPollsPendingThenExchangesAuthorizationCodeWithoutSavingOrLoggingSecrets() = runBlocking {
        var clock = 1000L
        val http = Http(200 to code(), 403 to JSONObject(),
            200 to JSONObject().put("authorization_code", "private-auth-code").put("code_verifier", "private-verifier"), 200 to token())
        val accounts = FriendProviderAccounts(http.client) { clock }
        val login = accounts.startLogin("codex")
        assertEquals("ABCD-EFGH", login.userCode)
        assertEquals("https://auth.openai.com/codex/device", login.verificationUrl)
        assertEquals(1900L, login.expiresAt)
        assertFalse(login.toString().contains("private-device-code"))
        assertTrue(accounts.pollLogin(login) is ProviderLoginResult.Pending)
        assertTrue(accounts.pollLogin(login) is ProviderLoginResult.Pending)
        assertEquals(2, http.requests.size)
        clock += 5
        val completed = accounts.pollLogin(login) as ProviderLoginResult.Complete
        assertEquals("account-a", completed.connection.credentials["account_id"])
        assertEquals("private-refresh", completed.connection.credentials["refresh_token"])
        assertEquals("openai_responses", completed.connection.apiFormat)
        assertEquals(4, http.requests.size)
        assertEquals(PROVIDER_CODEX_TOKEN, http.requests.last().url.toString())
        assertFalse(completed.toString().contains("private-refresh"))
        assertFalse(FriendModelConnectionCodec.encodeConfig(listOf(completed.connection)).toString().contains("private-refresh"))
        assertTrue(accounts.pollLogin(login) is ProviderLoginResult.Failed)
        assertEquals(4, http.requests.size)
    }

    @Test fun cancelledAndExpiredDeviceFlowsCannotPollOrExposeACompletedConnection() = runBlocking {
        var clock = 1000L
        val http = Http(200 to code(), 200 to code())
        val accounts = FriendProviderAccounts(http.client) { clock }
        val cancelled = accounts.startLogin("codex").also { it.cancel() }
        assertTrue(accounts.pollLogin(cancelled) is ProviderLoginResult.Failed)
        val expired = accounts.startLogin("codex")
        clock = expired.expiresAt
        assertTrue(accounts.pollLogin(expired) is ProviderLoginResult.Failed)
        assertEquals(2, http.requests.size)
    }

    @Test fun cancellationDuringTokenExchangeDiscardsReturnedCredentials() = runBlocking {
        val http = Http(200 to code(), 200 to JSONObject().put("authorization_code", "private-code").put("code_verifier", "private-verifier"), 200 to token())
        val accounts = FriendProviderAccounts(http.client) { 1000L }
        val login = accounts.startLogin("codex")
        http.beforeReply = { if (it.url.toString() == PROVIDER_CODEX_TOKEN) login.cancel() }
        val result = accounts.pollLogin(login)
        assertTrue(result is ProviderLoginResult.Failed)
        assertFalse(result.toString().contains("private-refresh"))
    }

    @Test fun githubDeviceFlowExchangesGithubIdentityForCopilotAndPinsTheReturnedApiHost() = runBlocking {
        val start = JSONObject().put("device_code", "github-device").put("user_code", "GH-CODE")
            .put("verification_uri", "https://github.com/login/device").put("expires_in", 900)
        val http = Http(200 to start, 200 to JSONObject().put("access_token", "github-user-token"),
            200 to JSONObject().put("token", "copilot-bearer").put("expires_at", 9000)
                .put("endpoints", JSONObject().put("api", "https://api.individual.githubcopilot.com")))
        val accounts = FriendProviderAccounts(http.client) { 1000L }
        val login = accounts.startLogin("github_copilot")
        val connection = (accounts.pollLogin(login) as ProviderLoginResult.Complete).connection
        assertEquals("github-user-token", connection.credentials["github_token"])
        assertEquals("copilot-bearer", connection.credentials["access_token"])
        assertEquals("token github-user-token", http.requests.last().header("Authorization"))
        assertEquals("https://api.individual.githubcopilot.com", providerCopilotEndpoint(connection))
        try {
            providerCopilotEndpoint(connection.copy(credentials = connection.credentials + ("api_endpoint" to "https://githubcopilot.com.attacker.test")))
            fail("Lookalike API endpoint accepted")
        } catch (error: FriendProviderFailure) { assertEquals("MODEL_AUTH_RESPONSE_INVALID", error.code) }
    }

    @Test fun xaiRejectsAnUntrustedDiscoveryIssuerBeforeStartingAuthorization() = runBlocking {
        val http = Http(200 to JSONObject().put("issuer", "https://attacker.test")
            .put("token_endpoint", "https://auth.x.ai/token").put("device_authorization_endpoint", "https://auth.x.ai/device"))
        try { FriendProviderAccounts(http.client).startLogin("xai_oauth"); fail() }
        catch (failure: FriendProviderFailure) { assertEquals("MODEL_AUTH_RESPONSE_INVALID", failure.code) }
        assertEquals(1, http.requests.size)
    }

    @Test fun pendingSlowDownChangesThePollingIntervalWithoutExposingUpstreamErrorText() = runBlocking {
        var clock = 1000L
        val start = JSONObject().put("device_code", "device").put("user_code", "code").put("verification_uri", "https://github.com/login/device")
        val http = Http(200 to start, 400 to JSONObject().put("error", "slow_down").put("error_description", "secret upstream text"),
            400 to JSONObject().put("error", "access_denied").put("error_description", "private refresh token"))
        val accounts = FriendProviderAccounts(http.client) { clock }
        val login = accounts.startLogin("github_copilot")
        val pending = accounts.pollLogin(login) as ProviderLoginResult.Pending
        assertTrue(pending.slowDown)
        assertEquals(10, pending.intervalSeconds)
        clock += 10
        val failed = accounts.pollLogin(login) as ProviderLoginResult.Failed
        assertFalse(failed.message.contains("private"))
        assertFalse(failed.message.contains("upstream"))
    }

    @Test fun workbuddyRefreshPreservesIdentityAndOnlyReturnsNewCredentialsForTheCallerToPersist() = runBlocking {
        val http = Http(200 to JSONObject().put("code", 0).put("data", JSONObject().put("accessToken", "new-access").put("expiresIn", 3600)))
        val old = providerNewConnection("workbuddy_cn", mapOf("access_token" to "old-access", "refresh_token" to "old-refresh",
            "expires_at" to "0", "uid" to "user-id", "enterprise_id" to "enterprise")).copy(baseUrl = "https://attacker.test")
        val refreshed = FriendProviderAccounts(http.client) { 1000L }.refresh(old)
        assertEquals("new-access", refreshed.credentials["access_token"])
        assertEquals("old-refresh", refreshed.credentials["refresh_token"])
        assertEquals("user-id", refreshed.credentials["uid"])
        assertEquals("old-access", old.credentials["access_token"])
        assertEquals(old.generation, refreshed.generation)
        assertEquals("https://copilot.tencent.com/v2/plugin/auth/token/refresh", http.requests.single().url.toString())
        assertEquals("old-refresh", http.requests.single().header("X-Refresh-Token"))
    }

    @Test fun freshTokensDoNotRefreshAndCodexAccountSwapsAreRejected() = runBlocking {
        val http = Http(200 to token("account-b"))
        val accounts = FriendProviderAccounts(http.client) { 1000L }
        val fresh = providerNewConnection("codex", mapOf("access_token" to jwt("account-a"), "refresh_token" to "private-refresh",
            "expires_at" to "9000", "account_id" to "account-a"))
        assertSame(fresh, accounts.refresh(fresh))
        assertTrue(http.requests.isEmpty())
        try { accounts.refresh(fresh.copy(credentials = fresh.credentials + ("expires_at" to "0"))); fail() }
        catch (failure: FriendProviderFailure) { assertEquals("MODEL_ACCOUNT_CHANGED", failure.code) }
        assertEquals("account-a", fresh.credentials["account_id"])
    }

    @Test fun catalogUsesOnlyVisibleAccountModelsAndQuotaKeepsUpstreamUnits() = runBlocking {
        val models = JSONObject().put("models", org.json.JSONArray().put(JSONObject().put("slug", "visible-model").put("visibility", "list"))
            .put(JSONObject().put("slug", "hidden-model").put("visibility", "hide")))
        val quota = JSONObject().put("plan_type", "fixture").put("credits", JSONObject().put("balance", "123.4")).put("access_token", "must-not-return")
        val http = Http(200 to models, 200 to quota)
        val connection = providerNewConnection("codex", mapOf("access_token" to "access", "account_id" to "account-a", "expires_at" to "9000"))
        val accounts = FriendProviderAccounts(http.client) { 1000L }
        assertEquals(listOf("visible-model"), accounts.fetchModels(connection))
        val result = accounts.fetchQuota(connection)
        assertEquals("123.4", result.getJSONObject("data").getJSONObject("credits").getString("balance"))
        assertFalse(result.toString().contains("must-not-return"))
        assertEquals("account-a", http.requests.first().header("chatgpt-account-id"))
    }

    @Test fun redirectResponsesNeverForwardDeviceFlowMaterialToALocation() = runBlocking {
        val http = Http(302 to JSONObject().put("message", "private-server-message"))
        try { FriendProviderAccounts(http.client).startLogin("codex"); fail() }
        catch (failure: FriendProviderFailure) {
            assertEquals("MODEL_REDIRECT_REJECTED", failure.code)
            assertFalse(failure.message!!.contains("private-server-message"))
        }
        assertEquals(1, http.requests.size)
    }

    @Test fun xaiUsesThePinnedDiscoveredDeviceAndRefreshEndpointsWithItsOwnClientAndScope() = runBlocking {
        val discovery = JSONObject().put("issuer", "https://auth.x.ai").put("token_endpoint", "https://auth.x.ai/oauth/token")
            .put("device_authorization_endpoint", "https://auth.x.ai/oauth/device")
        val device = JSONObject().put("device_code", "xai-device").put("user_code", "XAI-CODE").put("verification_uri", "https://auth.x.ai/device")
        val http = Http(200 to discovery, 200 to device,
            200 to JSONObject().put("access_token", "xai-first").put("refresh_token", "xai-refresh").put("expires_in", 3600),
            200 to discovery, 200 to JSONObject().put("access_token", "xai-rotated").put("refresh_token", "xai-refresh-rotated").put("expires_in", 3600))
        val accounts = FriendProviderAccounts(http.client) { 1000L }
        val login = accounts.startLogin("xai_oauth")
        val first = (accounts.pollLogin(login) as ProviderLoginResult.Complete).connection
        val rotated = accounts.refresh(first.copy(credentials = first.credentials + ("expires_at" to "0")))
        assertEquals("xai-rotated", rotated.credentials["access_token"])
        assertEquals("xai-refresh-rotated", rotated.credentials["refresh_token"])
        assertEquals("https://auth.x.ai/oauth/device", http.requests[1].url.toString())
        assertEquals("https://auth.x.ai/oauth/token", http.requests.last().url.toString())
        val buffer = okio.Buffer(); http.requests.last().body!!.writeTo(buffer)
        val form = buffer.readUtf8()
        assertTrue(form.contains("client_id=$PROVIDER_XAI_CLIENT"))
        assertTrue(form.contains("grant_type=refresh_token"))
        assertFalse(form.contains("github"))
    }

    @Test fun geminiPoolRefreshResolvesOwnedProjectsAndCatalogWithoutMutatingImportedCredentials() = runBlocking {
        val poolDoc = JSONObject().put("mode", "manual").put("accounts", org.json.JSONArray()
            .put(JSONObject().put("refresh", "pool-refresh").put("projectId", "untrusted-project")))
        val imported = providerImportDocument("gemini_pool", poolDoc.toString())
        val available = JSONObject().put("models", JSONObject()
            .put("visible", JSONObject().put("quotaInfo", JSONObject().put("remainingFraction", 0.4)))
            .put("hidden", JSONObject().put("isInternal", true)))
        val http = Http(200 to JSONObject().put("access_token", "pool-access").put("expires_in", 3600),
            200 to JSONObject().put("cloudaicompanionProject", JSONObject().put("id", "verified-project")), 200 to available)
        val refreshed = FriendProviderAccounts(http.client) { 1000L }.refresh(imported)
        val account = providerPoolAccounts(refreshed).single()
        assertEquals("verified-project", account.getString("project_id"))
        assertEquals("pool-access", account.getString("access_token"))
        assertEquals(listOf("visible"), account.getJSONArray("model_ids").let { ids -> (0 until ids.length()).map { ids.getString(it) } })
        assertEquals(0.4, account.getJSONObject("model_quota").getDouble("visible"), 0.00001)
        assertFalse(providerPoolAccounts(imported).single().has("project_id"))
        assertEquals("https://oauth2.googleapis.com/token", http.requests.first().url.toString())
        assertEquals("Bearer pool-access", http.requests.last().header("Authorization"))
    }

    @Test fun quotaOnlySubscriptionCanQueryTheRealUsageProtocolWithoutEnablingInference() = runBlocking {
        val http = Http(200 to JSONObject().put("five_hour", JSONObject().put("utilization", 12).put("resets_at", "future")))
        val connection = providerImportDocument("claude_subscription", JSONObject().put("claudeAiOauth",
            JSONObject().put("accessToken", "private-quota-token").put("expiresAt", 9_000_000L)).toString())
        val quota = FriendProviderAccounts(http.client) { 1000L }.fetchQuota(connection)
        assertTrue(quota.getBoolean("supported"))
        assertEquals(12, quota.getJSONObject("data").getJSONObject("five_hour").getInt("utilization"))
        assertEquals("https://api.anthropic.com/api/oauth/usage", http.requests.single().url.toString())
        assertEquals("oauth-2025-04-20", http.requests.single().header("anthropic-beta"))
        assertEquals("false", connection.metadata["inference"])
    }
}
