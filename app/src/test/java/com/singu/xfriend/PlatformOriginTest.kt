package com.singu.xfriend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlatformOriginTest {
    @Test fun normalizesBareDomainAndTrailingSlash() {
        assertEquals("https://www.ciyuancenter.cc", PlatformClient.normalizeOrigin(" www.ciyuancenter.cc/ "))
        assertEquals("https://example.com:8443", PlatformClient.normalizeOrigin("https://example.com:8443/"))
    }
    @Test fun refusesCredentialsPathsQueriesAndPlaintext() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com/api", "https://example.com?token=a", "https://example.com#fragment", "https://example.com:99999").forEach { raw ->
            assertThrows("Rejected origin: $raw", IllegalArgumentException::class.java) { PlatformClient.normalizeOrigin(raw) }
        }
    }
}
