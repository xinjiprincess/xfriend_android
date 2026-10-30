package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test

class CompanionOriginTest {
    @Test fun acceptHttpsAndUsbOnly() {
        assertEquals("https://desktop.example:8765", companionOrigin("https://desktop.example:8765/"))
        assertEquals("http://127.0.0.1:8765", companionOrigin("http://127.0.0.1:8765"))
    }
    @Test fun rejectCleartextLanAndCredentialsAndPaths() {
        listOf("http://192.168.1.1:8765", "https://user:password@desktop.example", "https://desktop.example/secret", "javascript:alert(1)", "https://desktop.example?token=x", "https://desktop.example#token").forEach {
            assertThrows("$it must be rejected", IllegalArgumentException::class.java) { companionOrigin(it) }
        }
    }
}
