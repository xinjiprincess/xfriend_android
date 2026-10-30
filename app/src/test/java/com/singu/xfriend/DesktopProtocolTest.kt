package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

class DesktopProtocolTest {
    private val ready = """{"application":"xfriend","protocol":1,"state":"ready","entry_path":"/chat"}"""
    private fun loopbackServer() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @Test fun validatesProductProtocolAndEntryPoint() {
        assertEquals("/chat", parseDesktopHandshake(ready).entryPath)
        listOf(ready.replace("xfriend", "other"), ready.replace("\"protocol\":1", "\"protocol\":2"), ready.replace("\"protocol\":1", "\"protocol\":\"1\""), ready.replace("/chat", "https://other.example"), ready.replace("/chat", "//other.example")).forEach {
            assertThrows(IllegalArgumentException::class.java) { parseDesktopHandshake(it) }
        }
    }
    @Test fun rejectsLoggedOutAndStartingComputers() {
        listOf("login_required", "starting", "unavailable").forEach {
            assertThrows(IllegalStateException::class.java) { parseDesktopHandshake(ready.replace("ready", it)) }
        }
    }
    @Test fun probesWithoutPhoneCloudCredentials() {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setBody(ready))
            assertEquals("/chat", probeDesktop("http://127.0.0.1:${server.port}").entryPath)
            val request = server.takeRequest()
            assertEquals("/mobile/status", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun doesNotFollowStatusRedirects() {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/collect"))
            assertThrows(IllegalStateException::class.java) { probeDesktop("http://127.0.0.1:${server.port}") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun rejectsOversizedStatusResponse() {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(65537)))
            assertThrows(IllegalArgumentException::class.java) { probeDesktop("http://127.0.0.1:${server.port}") }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun restoresPhoneModeForNewOrUnknownPreferences() {
        assertEquals(AppMode.PHONE, AppMode.restore(null))
        assertEquals(AppMode.PHONE, AppMode.restore("companion"))
        assertEquals(AppMode.DESKTOP, AppMode.restore("desktop"))
    }
}
