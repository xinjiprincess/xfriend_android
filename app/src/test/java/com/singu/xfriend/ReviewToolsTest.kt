package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewToolsTest {
    @Test fun retrievalFindsTailBeyondOldPrefixAndEnumerationRetainsEverything() {
        val text = "无关资料".repeat(9000) + "必须在年底完成预算审批"
        val resources = listOf(JSONObject().put("id", "knowledge1").put("name", "产品资料").put("kind", "knowledge").put("content", text))
        val hits = reviewSearchContext(resources, "预算审批")
        assertTrue(hits.getJSONArray("matches").getJSONObject(0).getString("text").contains("必须在年底"))
        val all = StringBuilder(); var offset = 0
        while (true) {
            val page = reviewSearchContext(resources, "", offset, "knowledge", "knowledge1")
            page.getJSONArray("matches").objects().forEach { all.append(it.getString("text")) }
            if (page.isNull("next_offset")) break
            offset = page.getInt("next_offset")
        }
        assertEquals(text, all.toString())
    }
    @Test fun completeProfilePagingHasExplicitContinuationWithoutLoss() {
        val text = "客户画像\\\"详细内容".repeat(10000)
        val output = StringBuilder(); var offset = 0
        while (true) {
            val page = reviewContextPage(text, offset, 6000)
            assertTrue(page.toString().length <= 8000)
            output.append(page.getString("text"))
            if (page.isNull("next_offset")) break
            offset = page.getInt("next_offset")
        }
        assertEquals(text, output.toString())
    }
    @Test fun expertActuallyCallsSharedSalesToolAndResumesAfterSavedToolResult() = runBlocking {
        var reads = 0; var proposals = 0
        val adapter = object : SalesContextAdapter {
            override suspend fun allows(expertId: String, customerId: String) = expertId == "expert" && customerId == "customer"
            override suspend fun readCustomer(expertId: String, customerId: String) = JSONObject().put("name", "客户")
            override suspend fun readHistory(expertId: String, customerId: String, query: String) = JSONObject()
            override suspend fun readEvidence(expertId: String, customerId: String, query: String): JSONObject { reads++; return JSONObject().put("id", "s1-1-e1").put("quote", "预算八万") }
            override suspend fun emitProposal(expertId: String, draft: SalesProposalDraft) { proposals++ }
        }
        val tools = SalesContextTools(adapter)
        val state = JSONObject(); var requests = 0
        val call = JSONObject().put("id", "call-1").put("type", "function").put("function", JSONObject()
            .put("name", "read_sales_evidence").put("arguments", JSONObject().put("customer_id", "customer").put("query", "预算").toString()))
        try {
            reviewToolLoop(state, "expert-0", "model", "system", "input", tools.definitions(), {}, { _ ->
                requests++
                if (requests > 1) throw CancellationException()
                JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
                    .put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call)))))
            }) { name, args -> tools.execute("expert", name, args) }
            fail("cancel expected")
        } catch (_: CancellationException) { }
        assertEquals(1, reads)
        val answer = reviewToolLoop(state, "expert-0", "model", "system", "input", tools.definitions(), {}, { body ->
            assertTrue(body.getJSONArray("messages").objects().any { it.optString("role") == "tool" && it.getString("content").contains("预算八万") })
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", "预算八万 [s1-1-e1]"))))
        }) { name, args -> tools.execute("expert", name, args) }
        assertEquals("预算八万 [s1-1-e1]", answer); assertEquals(1, reads); assertEquals(0, proposals)
        assertTrue(runCatching { tools.execute("expert", "read_sales_customer", JSONObject().put("customer_id", "another-customer")) }.isFailure)
    }
}
