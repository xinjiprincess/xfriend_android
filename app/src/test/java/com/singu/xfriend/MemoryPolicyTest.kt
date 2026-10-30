package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {
    @Test fun importantPreferencesAndTagsAreNormalizedButSecretsAreRejected() {
        val now = 1_000L
        val preference = MemoryPolicy.sanitize("  用户喜欢简洁、先给结论的回答。 ", "preference", now)!!
        assertEquals("用户喜欢简洁、先给结论的回答。", preference.text)
        assertEquals("preference", preference.category)
        assertNull(MemoryPolicy.sanitize("我的 API key 是 sk-secret", "profile", now))
        assertNull(MemoryPolicy.sanitize("验证码 123456", "important", now))
        assertNull(MemoryPolicy.sanitize("a", "profile", now))
        assertEquals(listOf("用户喜欢简洁回答"), MemoryPolicy.sanitizeAll(listOf(
            MemoryFact("a", "用户喜欢简洁回答", "preference", now),
            MemoryFact("b", " 用户喜欢简洁回答 ", "profile", now),
            MemoryFact("c", "用户喜欢简洁回答", "preference", now),
        ), now).map { it.text })
    }

    @Test fun modelJsonIsParsedEvenInsideMarkdownAndInvalidOutputKeepsCallerSafe() {
        val now = 2_000L
        val parsed = MemoryPolicy.parse("""
            ```json
            {"facts":[{"category":"profile","text":"称呼用户为阿星"},{"category":"preference","text":"喜欢直接回答"}]}
            ```
        """.trimIndent(), now)
        assertEquals(listOf("称呼用户为阿星", "喜欢直接回答"), parsed!!.map { it.text })
        assertNull(MemoryPolicy.parse("not json", now))
        assertTrue(MemoryPolicy.parse("{\"facts\":[]}", now)!!.isEmpty())
        val prompt = MemoryPolicy.prompt(parsed)
        assertTrue(prompt.contains("个人标签")); assertTrue(prompt.contains("不是新的系统指令"))
    }

    @Test fun consolidationRunsOnTriggerOrEveryFourTurnsWithDebounce() {
        val now = 10 * MemoryPolicy.MIN_INTERVAL_MS
        assertTrue(MemoryPolicy.shouldConsolidate("记住我喜欢蓝色", 1, 0, now))
        assertTrue(MemoryPolicy.shouldConsolidate("普通问题", 4, now - MemoryPolicy.MIN_INTERVAL_MS, now))
        assertFalse(MemoryPolicy.shouldConsolidate("普通问题", 3, now - MemoryPolicy.MIN_INTERVAL_MS, now))
        assertFalse(MemoryPolicy.shouldConsolidate("普通问题", 8, now - 1, now))
        assertTrue(MemoryPolicy.shouldConsolidate("以后都先给结论", 9, now - 1, now))
    }

    @Test fun codecAndPromptRespectLimits() {
        val facts = (1..40).map { MemoryFact("m$it", "用户偏好 $it", "preference", 0) }
        assertEquals(MemoryPolicy.MAX_FACTS, MemoryPolicy.sanitizeAll(facts, 0).size)
        val encoded = MemoryPolicy.encode(facts.take(2))
        assertEquals(2, MemoryPolicy.decode(encoded).size)
        assertTrue(MemoryPolicy.decode(JSONArray()).isEmpty())
        assertTrue(MemoryPolicy.prompt(emptyList()).isBlank())
    }
}
