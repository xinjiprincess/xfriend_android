package com.singu.xfriend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Catalog items keep source/license/dependencies and every supplied UTF-8 file, including scripts,
 * as inert JSON in the account-scoped Store. Only the primary prompt/markdown becomes a resource.
 */
internal fun marketResourceId(kind: String, id: String): String = "market-" + PortablePackCodec.sha256((kind + ":" + id).toByteArray()).take(24)

internal fun installMarketResource(store: Store, kind: String, entry: JSONObject): String = synchronized(store) {
    require(kind in setOf("experts", "skills"))
    val snapshot = portableJson(entry.toString().toByteArray())
    val id = marketResourceId(kind, snapshot.packString("id"))
    val name = snapshot.packString("name"); val content = snapshot.packString("content")
    require(snapshot.getString("id").isNotBlank() && name.isNotBlank() && content.isNotBlank())
    val files = snapshot.packObject("files")
    require(files.length() <= PortablePackCodec.MAX_FILES)
    var total = content.toByteArray().size.toLong()
    files.packKeys().forEach { path ->
        PortablePackCodec.safePath(path)
        val bytes = files.packString(path).toByteArray(); require(bytes.size <= PortablePackCodec.MAX_FILE); total += bytes.size
    }
    require(total <= PortablePackCodec.MAX_TOTAL)
    val experts = store.experts; val skills = store.skills; val metadata = store.marketResources
    try {
        if (kind == "experts") store.experts = experts.filterNot { it.id == id } + Expert(id, name, content)
        else store.skills = skills.filterNot { it.id == id } + Skill(id, name, content)
        store.marketResources = metadata + (id to snapshot.toString())
        store.save()
        id
    } catch (failure: Exception) {
        store.experts = experts; store.skills = skills; store.marketResources = metadata
        throw failure
    }
}

class StoreResourceMarketInstaller(private val store: Store) : ResourceMarketInstallRepository {
    override suspend fun commit(plan: ResourceMarketInstallPlan) {
        withContext(Dispatchers.IO) { installMarketResource(store, plan.item.type.path, plan.item.metadata()) }
    }
}
