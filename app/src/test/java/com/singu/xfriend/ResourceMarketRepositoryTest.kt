package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResourceMarketRepositoryTest {
    @Test fun agreedCatalogPathsAndOpaqueMetadataArePreserved() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val repo = ResourceMarketRepository { path, query ->
            requests.add(path to query)
            """{"items":[{"id":"one","name":"N","description":"D","version":"1","kind":"native","content":"正文","files":{"script.py":{"sha256":"opaque","url":"never-fetch"}},"dependencies":["remote-shell"],"license":"Apache-2.0","provenance":{"upstream":"catalog"}}],"cursor":"next"}""".toByteArray()
        }
        val expert = repo.experts("中文 & text", "sales")
        val skill = repo.skills("write")
        assertEquals("/api/v2/catalog/experts", requests[0].first)
        assertEquals(mapOf("q" to "中文 & text", "scene" to "sales", "page" to "1"), requests[0].second)
        assertEquals("/api/v2/catalog/skills", requests[1].first)
        assertEquals(mapOf("q" to "write", "limit" to "10"), requests[1].second)
        assertEquals("Apache-2.0", expert.items.single().metadata().getString("license"))
        assertEquals("never-fetch", expert.items.single().metadata().getJSONObject("files").getJSONObject("script.py").getString("url"))
        assertEquals("next", expert.metadata().getString("cursor"))
        assertFalse(ResourceMarketInstallPlan(skill.items.single()).executeScripts)
        expert.items.single().metadata().put("license", "changed")
        assertEquals("Apache-2.0", expert.items.single().metadata().getString("license"))
    }

    @Test fun duplicateIdsAndMalformedResponsesFail() = runBlocking {
        for (json in listOf("""{"items":[],"items":[]}""", """{"items":[{"id":"x"}]}""", """{"items":[{"id":"x","name":"a","description":"","version":"1","kind":"skill","content":"a"},{"id":"x","name":"b","description":"","version":"1","kind":"skill","content":"b"}]}""")) {
            try { ResourceMarketRepository { _, _ -> json.toByteArray() }.skills(); fail("Accepted malformed catalog") }
            catch (_: IllegalArgumentException) { }
            catch (_: org.json.JSONException) { }
        }
    }
}
