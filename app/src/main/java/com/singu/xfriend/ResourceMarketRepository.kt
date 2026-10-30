package com.singu.xfriend

import org.json.JSONObject

enum class ResourceMarketKind(val path: String) { EXPERT("experts"), SKILL("skills") }

/** Transport belongs to CloudAccount's session: renew bearer tokens there, never accept owner IDs
 * from catalog data. No login, token storage, redirect handling or parallel cloud client here.
 */
fun interface ResourceMarketTransport {
    suspend fun get(path: String, query: Map<String, String>): ByteArray
}

/** Preserve upstream license/provenance and opaque file/dependency metadata without guessing
 * their encoding. File fetching/activation is a separate explicit host installation decision.
 */
class ResourceMarketItem internal constructor(val type: ResourceMarketKind, json: JSONObject) {
    private val document = json.toString()
    val id: String = json.getString("id")
    val name: String = json.getString("name")
    val description: String = json.getString("description")
    val version: String = json.getString("version")
    val kind: String = json.getString("kind")
    val content: String = json.getString("content")
    fun metadata(): JSONObject = JSONObject(document)
}

class ResourceMarketPage internal constructor(val items: List<ResourceMarketItem>, json: JSONObject) {
    private val document = json.toString()
    fun metadata(): JSONObject = JSONObject(document)
}

class ResourceMarketRepository(private val transport: ResourceMarketTransport) {
    suspend fun experts(query: String = "", scene: String = "", page: Int = 1): ResourceMarketPage {
        require(page >= 1)
        return fetch(ResourceMarketKind.EXPERT, linkedMapOf("q" to query, "scene" to scene, "page" to page.toString()))
    }
    suspend fun skills(query: String = "", limit: Int = 10): ResourceMarketPage {
        require(limit in 1..10)
        return fetch(ResourceMarketKind.SKILL, mapOf("q" to query, "limit" to limit.toString()))
    }

    private suspend fun fetch(type: ResourceMarketKind, query: Map<String, String>): ResourceMarketPage {
        val bytes = transport.get("/api/v2/catalog/${type.path}", query)
        require(bytes.size <= PortablePackCodec.MAX_FILE) { "市场响应过大" }
        val document = portableJson(bytes)
        val rows = document.getJSONArray("items")
        require(rows.length() <= 2000) { "市场资源数量过多" }
        val seen = mutableSetOf<String>()
        val items = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            listOf("id", "name", "description", "version", "kind", "content").forEach { row.packString(it) }
            require(row.getString("id").isNotBlank() && seen.add(row.getString("id"))) { "市场资源标识无效" }
            ResourceMarketItem(type, row)
        }
        return ResourceMarketPage(items, document)
    }
}

/** Staging retains all metadata and license text. It grants no script execution or download rights. */
data class ResourceMarketInstallPlan(val item: ResourceMarketItem, val trustSkillText: Boolean = false) {
    val executeScripts: Boolean get() = false
}

fun interface ResourceMarketInstallRepository {
    suspend fun commit(plan: ResourceMarketInstallPlan)
}
