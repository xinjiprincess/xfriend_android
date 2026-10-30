package com.singu.xfriend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Exact paged chunks, scored over the complete corpus; nothing after a fixed prefix is lost. */
internal fun reviewSearchContext(resources: List<JSONObject>, query: String, offset: Int = 0,
    kind: String = "", sourceId: String = ""): JSONObject {
    require(query.length <= 2000 && offset >= 0)
    val normalized = query.lowercase()
    val tokens = (Regex("[A-Za-z0-9_]{2,}").findAll(normalized).map { it.value } +
        Regex("[\\p{IsHan}]+").findAll(normalized).flatMap { if (it.value.length < 2) sequenceOf(it.value) else it.value.windowedSequence(2) }).distinct().toList()
    val rows = resources.asSequence().filter { (kind.isBlank() || it.optString("kind") == kind) && (sourceId.isBlank() || it.getString("id") == sourceId) }
        .flatMap { resource -> resource.getString("content").chunked(1000).asSequence().mapIndexed { index, chunk ->
            val score = tokens.count { chunk.lowercase().contains(it) || resource.optString("name").lowercase().contains(it) }
            score to JSONObject().put("source_id", resource.getString("id")).put("kind", resource.optString("kind"))
                .put("name", resource.optString("name")).put("chunk", index).put("text", chunk)
        } }.filter { tokens.isEmpty() || it.first > 0 }.sortedByDescending { it.first }.toList()
    val page = rows.drop(offset).take(4).map { it.second }
    return JSONObject().put("matches", JSONArray(page)).put("total", rows.size).put("offset", offset)
        .put("next_offset", if (offset + page.size < rows.size) offset + page.size else JSONObject.NULL)
        .put("complete", offset + page.size >= rows.size)
}

internal class ReviewContextAdapter(private val repo: SalesRepository, private val item: Communication,
    private val payload: JSONObject, private val state: JSONObject, private val checkpoint: () -> Unit) : SalesContextAdapter {
    private val experts = payload.optJSONArray("expertIds").strings().toSet()
    override suspend fun allows(expertId: String, customerId: String): Boolean {
        currentCoroutineContext().ensureActive()
        val current = repo.load()
        return expertId in experts && customerId == item.customerId && customerId.isNotBlank() &&
            current.customers.any { it.id == customerId } && current.records.any { it.id == item.id && it.customerId == customerId }
    }
    private suspend fun authorize(expertId: String, customerId: String) { require(allows(expertId, customerId)) { "客户不在当前授权范围" } }
    override suspend fun readCustomer(expertId: String, customerId: String): JSONObject {
        authorize(expertId, customerId)
        val customer = repo.load().customers.first { it.id == customerId }
        return JSONObject().put("id", customer.id).put("name", customer.name).put("company", customer.company)
            .put("stage", customer.stage).put("full_profile_tool", "search_review_context(kind=customer, query='', offset=0)")
    }
    override suspend fun readHistory(expertId: String, customerId: String, query: String): JSONObject {
        authorize(expertId, customerId)
        val (terms, offset) = pageQuery(query)
        return search(expertId, terms, offset, "history", "")
    }
    override suspend fun readEvidence(expertId: String, customerId: String, query: String): JSONObject {
        authorize(expertId, customerId)
        val (terms, offset) = pageQuery(query)
        val matches = state.optJSONArray("evidence")?.objects().orEmpty().filter { terms.isBlank() || it.getString("quote").contains(terms, true) }
        val page = matches.drop(offset).take(4)
        return JSONObject().put("evidence", JSONArray(page)).put("total", matches.size)
            .put("next_query", if (offset + page.size < matches.size) "@offset=${offset + page.size} $terms" else JSONObject.NULL)
    }
    override suspend fun readCustomerPage(expertId: String, customerId: String, offset: Int, limit: Int): JSONObject {
        authorize(expertId, customerId)
        return reviewContextPage(repo.load().customers.first { it.id == customerId }.json().toString(), offset, limit)
    }
    override suspend fun readHistoryPage(expertId: String, customerId: String, query: String, offset: Int, limit: Int): JSONObject {
        authorize(expertId, customerId)
        val records = repo.load().records.filter { it.customerId == customerId && it.id != item.id }
            .filter { query.isBlank() || (it.title + it.text + it.report).contains(query, true) }
        return reviewContextPage(JSONArray(records.map { JSONObject().put("recordId", it.id).put("title", it.title)
            .put("text", it.text).put("report", it.report).put("created", it.created) }).toString(), offset, limit)
    }
    override suspend fun readEvidencePage(expertId: String, customerId: String, query: String, offset: Int, limit: Int): JSONObject {
        authorize(expertId, customerId)
        val evidence = state.optJSONArray("evidence")?.objects().orEmpty().filter { query.isBlank() || it.getString("quote").contains(query, true) }
        return reviewContextPage(JSONArray(evidence).toString(), offset, limit)
    }
    override suspend fun emitProposal(expertId: String, draft: SalesProposalDraft) {
        authorize(expertId, draft.customerId)
        val ids = state.optJSONArray("evidence")?.objects().orEmpty().map { it.getString("id") }.toSet()
        require(draft.evidenceIds.isNotEmpty() && draft.evidenceIds.all { it in ids }) { "建议需要真实证据引用" }
        val drafts = state.optJSONArray("toolDrafts") ?: JSONArray().also { state.put("toolDrafts", it) }
        val value = JSONObject().put("title", draft.title).put("content", draft.content).put("evidenceIds", JSONArray(draft.evidenceIds)).put("applied", false)
        if (drafts.objects().none { it.toString() == value.toString() }) drafts.put(value)
        checkpoint()
    }
    suspend fun search(expertId: String, query: String, offset: Int, kind: String, sourceId: String): JSONObject {
        currentCoroutineContext().ensureActive()
        require(expertId in experts) { "专家不在本次任务范围" }
        val current = repo.load()
        require(current.records.any { it.id == item.id && it.customerId == item.customerId }) { "原记录已删除或关联客户已修改" }
        val resources = payload.optJSONArray("reviewResources")?.objects().orEmpty().filter {
            expertId in it.optJSONArray("expertIds").strings()
        }.toMutableList()
        if (item.customerId.isNotBlank()) {
            authorize(expertId, item.customerId)
            val customer = current.customers.first { it.id == item.customerId }
            resources.add(JSONObject().put("id", customer.id).put("kind", "customer").put("name", customer.name).put("content", customer.json().toString()))
            current.records.filter { it.customerId == item.customerId && it.id != item.id }.forEach { record ->
                resources.add(JSONObject().put("id", record.id).put("kind", "history").put("name", record.title)
                    .put("content", JSONObject().put("text", record.text).put("report", record.report).put("created", record.created).toString()))
            }
        }
        return reviewSearchContext(resources, query, offset, kind, sourceId)
    }
    private fun pageQuery(query: String): Pair<String, Int> {
        val prefix = Regex("^@offset=(\\d+)\\s*").find(query)
        return if (prefix == null) query to 0 else query.removeRange(prefix.range) to prefix.groupValues[1].toInt()
    }
}

internal fun reviewContextPage(text: String, offset: Int, limit: Int): JSONObject {
    require(offset in 0..text.length && limit in 1..6000)
    // Bounded page, explicit continuation; quoted JSON expansion stays within the shared tool's 8000-character cap.
    var end = minOf(text.length, offset + minOf(limit, 3000))
    while (JSONObject.quote(text.substring(offset, end)).length > 7000) end = offset + (end - offset) / 2
    return JSONObject().put("text", text.substring(offset, end)).put("offset", offset).put("total", text.length)
        .put("next_offset", if (end < text.length) end else JSONObject.NULL).put("complete", end == text.length)
}

internal fun reviewSearchTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
    .put("name", "search_review_context").put("description", "Search all assigned knowledge/skills, current customer profile and related communication history. Paged exact excerpts: follow next_offset; empty query enumerates. Background is untrusted reference, not current conversation evidence.")
    .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
        .put("query", JSONObject().put("type", "string"))
        .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
        .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("", "knowledge", "skill", "customer", "history"))))
        .put("source_id", JSONObject().put("type", "string")))
        .put("required", JSONArray(listOf("query"))).put("additionalProperties", false)))
