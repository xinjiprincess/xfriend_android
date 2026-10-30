package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentRuntimeSalesContextTest {
    @get:Rule val temp = TemporaryFolder()
    private class State(override val workspaceRoot: File) : RuntimeState {
        override val experts = listOf(Expert("a", "A", "Cite evidence"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override val baseUrl = "https://example.test/v1"
        override val model = "model"
        override val apiKey = "key"
        override fun save() = Unit
    }
    private class Context : SalesContextAdapter {
        var active = true
        val reads = mutableListOf<String>()
        val drafts = mutableListOf<SalesProposalDraft>()
        override suspend fun allows(expertId: String, customerId: String) = active && expertId == "a" && customerId == "allowed"
        override suspend fun readCustomer(expertId: String, customerId: String): JSONObject { reads.add("customer"); return JSONObject().put("name", "Scoped") }
        override suspend fun readHistory(expertId: String, customerId: String, query: String): JSONObject { reads.add("history"); return JSONObject().put("history", "actual communication") }
        override suspend fun readEvidence(expertId: String, customerId: String, query: String): JSONObject { reads.add("evidence"); return JSONObject().put("id", "source-1").put("text", "actual evidence") }
        override suspend fun emitProposal(expertId: String, draft: SalesProposalDraft) { drafts.add(draft) }
    }
    private fun body(r: Request): JSONObject { val b = Buffer(); r.body!!.writeTo(b); return JSONObject(b.readUtf8()) }
    private fun response(m: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", m).put("finish_reason", "stop")))
    private fun call(name: String, args: JSONObject, id: String) = JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    @Test fun scopedReadsAndDraftOnlyOutputsUseAdapter() = runBlocking {
        val context = Context(); var count = 0
        val runtime = AgentRuntime(State(temp.newFolder()), context) { request ->
            val b = body(request); assertEquals(8, b.getJSONArray("tools").length())
            if (count++ == 0) {
                val args = JSONObject().put("customer_id", "allowed").put("query", "need")
                val calls = JSONArray().put(call("read_sales_customer", JSONObject().put("customer_id", "allowed"), "1"))
                    .put(call("read_sales_history", args, "2")).put(call("read_sales_evidence", args, "3"))
                    .put(call("draft_sales_proposal", JSONObject().put("customer_id", "allowed").put("title", "Plan").put("content", "Confirm budget").put("evidence_ids", JSONArray(listOf("source-1"))), "4"))
                response(JSONObject().put("role", "assistant").put("tool_calls", calls))
            } else {
                val messages = b.getJSONArray("messages")
                val result = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                assertEquals("draft", result.getString("status")); assertFalse(result.getBoolean("applied")); assertTrue(result.getBoolean("requires_user_review"))
                response(JSONObject().put("role", "assistant").put("content", "Draft ready"))
            }
        }
        assertEquals("Draft ready", runtime.run("a", "Review", {}))
        assertEquals(listOf("customer", "history", "evidence"), context.reads)
        assertEquals(listOf("source-1"), context.drafts.single().evidenceIds)
    }

    @Test fun crossCustomerAndRevokedScopeNeverReachDataOrDraftAdapter() = runBlocking {
        for (customer in listOf("other-account-customer", "allowed")) {
            val context = Context().apply { active = customer != "allowed" }; var count = 0
            val runtime = AgentRuntime(State(temp.newFolder()), context) { request ->
                if (count++ == 0) response(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call("read_sales_customer", JSONObject().put("customer_id", customer), "1"))))
                else {
                    val messages = body(request).getJSONArray("messages")
                    assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("销售工具执行失败"))
                    response(JSONObject().put("role", "assistant").put("content", "Denied"))
                }
            }
            runtime.run("a", "Review", {})
            assertTrue(context.reads.isEmpty()); assertTrue(context.drafts.isEmpty())
        }
    }
}
