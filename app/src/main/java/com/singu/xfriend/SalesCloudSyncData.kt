package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject

internal data class SalesCloudResources(val experts: List<Expert>, val teams: List<Team>, val skills: List<Skill>, val knowledge: List<Knowledge>, val metadata: Map<String, String> = emptyMap())

internal fun salesCloudSnapshot(data: SalesData, resources: SalesCloudResources): Map<String, SalesCloudEntity> {
    val result = mutableListOf<SalesCloudEntity>()
    fun add(type: String, id: String, json: JSONObject) {
        resources.metadata["$type:$id"]?.let { raw ->
            val extra = JSONObject(raw)
            extra.keys().forEach { key -> if (!json.has(key)) json.put(key, extra.get(key)) }
        }
        result.add(SalesCloudEntity(type, id, salesCloudCanonical(json)))
    }
    data.customers.forEach { add("customer", it.id, it.json()) }
    data.records.forEach {
        // File names/URIs are local device references, never remotely resolvable media.
        add("communication", it.id, it.json().also { j -> j.remove("file"); j.remove("attachments") })
    }
    data.opportunities.forEach { add("opportunity", it.id, it.json()) }
    data.followUps.forEach { add("followup", it.id, it.json()) }
    data.profiles.forEach {
        val j = it.json().also { json -> json.remove("key"); json.remove("syncSecret") }.put("sync_secrets", it.syncSecret)
        if (it.syncSecret) j.put("apiKey", it.key)
        add("model_profile", it.id, j)
    }
    resources.experts.forEach { add("expert", it.id, JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt).put("skills", JSONArray(it.skills)).put("knowledge", JSONArray(it.knowledge))) }
    resources.teams.forEach { add("team", it.id, JSONObject().put("id", it.id).put("name", it.name).put("members", JSONArray(it.members))) }
    resources.skills.forEach { add("skill", it.id, JSONObject().put("id", it.id).put("name", it.name).put("content", it.content)) }
    resources.knowledge.forEach { add("knowledge", it.id, JSONObject().put("id", it.id).put("name", it.name).put("content", it.content)) }
    require(result.map { it.key }.distinct().size == result.size) { "本地同步实体标识重复" }
    return result.associateBy { it.key }
}

internal fun salesCloudApplySales(original: SalesData, entities: List<SalesCloudEntity>): SalesData {
    var data = original
    entities.forEach { value ->
        val j = JSONObject(value.payload).put("id", value.id)
        when (value.type) {
            "customer" -> data = if (value.deleted) data.withoutCustomer(value.id)
                else data.copy(customers = data.customers.filterNot { it.id == value.id } + Customer.read(j))
            "communication" -> data = if (value.deleted) data.withoutRecord(value.id) else {
                val existing = data.records.find { it.id == value.id }
                val record = Communication.read(j.put("file", existing?.file.orEmpty()).put("attachments", JSONArray(existing?.attachments.orEmpty())))
                data.copy(records = data.records.filterNot { it.id == value.id } + record)
            }
            "opportunity" -> data = data.copy(opportunities = data.opportunities.filterNot { it.id == value.id } + if (value.deleted) emptyList() else listOf(Opportunity.read(j)))
            "followup" -> data = data.copy(followUps = data.followUps.filterNot { it.id == value.id } + if (value.deleted) emptyList() else listOf(FollowUp.read(j)))
            "model_profile" -> {
                val existing = data.profiles.find { it.id == value.id }
                val optedIn = j.optBoolean("sync_secrets") || j.optBoolean("syncSecret")
                val key = if (optedIn && (j.has("apiKey") || j.has("key"))) j.optString("apiKey", j.optString("key")) else existing?.key.orEmpty()
                data = data.copy(profiles = data.profiles.filterNot { it.id == value.id } + if (value.deleted) emptyList()
                    else listOf(ModelProfile.read(j.put("key", key).put("syncSecret", optedIn))),
                    active = if (value.deleted && data.active == value.id) "" else data.active)
            }
        }
    }
    return data
}

internal fun salesCloudApplyResources(original: SalesCloudResources, entities: List<SalesCloudEntity>): SalesCloudResources {
    var result = original
    entities.forEach { value ->
        val j = JSONObject(value.payload)
        when (value.type) {
            "expert" -> result = result.copy(experts = result.experts.filterNot { it.id == value.id } + if (value.deleted) emptyList()
                else listOf(Expert(value.id, j.getString("name"), j.getString("prompt"), j.optJSONArray("skills").strings(), j.optJSONArray("knowledge").strings())))
            "team" -> result = result.copy(teams = result.teams.filterNot { it.id == value.id } + if (value.deleted) emptyList()
                else listOf(Team(value.id, j.getString("name"), j.optJSONArray("members").strings())))
            "skill" -> result = result.copy(skills = result.skills.filterNot { it.id == value.id } + if (value.deleted) emptyList()
                else listOf(Skill(value.id, j.getString("name"), j.getString("content"))))
            "knowledge" -> result = result.copy(knowledge = result.knowledge.filterNot { it.id == value.id } + if (value.deleted) emptyList()
                else listOf(Knowledge(value.id, j.getString("name"), j.getString("content"))))
        }
    }
    return result
}
