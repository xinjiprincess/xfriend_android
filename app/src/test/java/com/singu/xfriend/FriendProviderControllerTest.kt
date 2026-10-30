package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FriendProviderControllerTest {
    @Test fun quotaSummaryReadsKnownNumbersWithoutEchoingIdentityCredentialsOrArbitraryText() {
        val marker = "private-identity-or-credential-must-not-appear"
        val payload = JSONObject().put("supported", true).put("access_token", marker).put("data", JSONObject()
            .put("email", marker).put("account_id", marker).put("message", marker)
            .put("rate_limit", JSONObject().put("primary_window", JSONObject().put("used_percent", 29.5).put("token", marker))))

        val notice = friendProviderQuotaNotice(payload)
        assertTrue(notice.contains("主要窗口已用 29.5%"))
        assertFalse(notice.contains(marker))
        assertFalse(notice.contains("account_id"))
        assertFalse(notice.contains("access_token"))
    }

    @Test fun quotaSummaryRejectsStringsNegativeNumbersAndOutOfRangePercentages() {
        val payload = JSONObject().put("supported", true).put("data", JSONObject()
            .put("used", -1).put("remaining", "unsafe-account-field")
            .put("limit", Long.MAX_VALUE)
            .put("five_hour", JSONObject().put("utilization", 101))
            .put("seven_day", JSONObject().put("utilization", -0.1))
            .put("rate_limit", JSONObject().put("primary_window", JSONObject().put("used_percent", "50"))))

        assertEquals("额度查询完成；服务未提供可安全展示的标准额度字段。", friendProviderQuotaNotice(payload))
    }

    @Test fun quotaSummaryHasSixMetricLimitAndNeverUsesPoolAccountOrModelLabels() {
        val rows = JSONArray((1..12).map { JSONObject().put("remainingFraction", 0.25).put("modelId", "private-model-label") })
        val payload = JSONObject().put("supported", true).put("data", JSONObject().put("accounts", JSONArray()
            .put(JSONObject().put("account_id", "private-account-label").put("buckets", rows))))

        val notice = friendProviderQuotaNotice(payload)
        assertEquals(6, Regex("25%").findAll(notice).count())
        assertFalse(notice.contains("private-model-label"))
        assertFalse(notice.contains("private-account-label"))
        assertFalse(notice.contains("配额 7"))
    }

    @Test fun unsupportedQuotaDoesNotPretendThatAUsagePayloadProvesAvailability() {
        val payload = JSONObject().put("supported", false).put("data", JSONObject().put("remaining", 999))
        assertEquals("此渠道未提供可查询的账号额度。", friendProviderQuotaNotice(payload))
        assertEquals("额度查询完成；服务未提供可安全展示的标准额度字段。",
            friendProviderQuotaNotice(JSONObject().put("supported", true).put("data", JSONObject().put("unknown_credit_total", 100))))
    }

    @Test fun arbitraryExceptionMessagesAndModelFailureTextCannotLeakThroughControllerErrors() {
        val secret = "private-request-url-header-or-token"
        listOf(IOException(secret), IllegalArgumentException(secret), RuntimeException(secret), FriendModelFailure("HTTP_401", secret),
            FriendModelFailure("UNKNOWN_$secret", secret)).forEach { failure ->
            val error = friendProviderControllerError(failure)
            assertFalse(error.contains(secret))
            assertTrue(error.isNotBlank())
        }
        assertEquals("账号授权失效，请重新授权", friendProviderControllerError(
            FriendProviderFailure("MODEL_AUTH_REQUIRED", "账号授权失效，请重新授权")))
    }
}
