package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Merge these extensions into the sync adapter's normal Android payloads. Never replace its
 * encrypted unknown-field shadow with this map, and never include Store.portableSecrets here.
 */
fun portableSyncMetadata(store: Store): Map<String, JSONObject> = synchronized(store) {
    portableSyncMetadata(StorePortableInstallState(store))
}

internal fun portableSyncMetadata(store: PortableInstallState): Map<String, JSONObject> {
    val result = linkedMapOf<String, JSONObject>()
    store.portablePackages.forEach { (packageId, stored) ->
        // Sync itself supports one-member teams; only explicit xfpack export rejects them.
        // Primary Android fields override this preserved definition when reconstructing a pack.
        val pack = PortablePackCodec.decode(File(store.portableRoot, stored.archiveName).readBytes())
        val manifest = JSONObject(stored.manifestJson); val files = pack.files().toMutableMap()
        val providers = manifest.packArray("providers")
        providers.objects().forEach { it.remove("has_secret") }
        val experts = manifest.packArray("experts").objects()
        experts.forEach { e ->
            val mappings = e.packObject("files")
            val root = workspacePath(store.workspaceRoot, stored.resourceIds.getValue(e.getString("ref")))
            mappings.packKeys().forEach { relative ->
                val file = workspacePath(root, PortablePackCodec.safePath(relative))
                require(file.isFile && file.length() <= PortablePackCodec.MAX_FILE) { "同步包内文件缺失或过大" }
                val path = "assets/mobile/" + PortablePackCodec.sha256((e.getString("ref") + "\u0000" + relative).toByteArray())
                files[path] = file.readBytes(); mappings.put(relative, path)
            }
            e.put("files", mappings)
            val encoded = JSONObject()
            val paths = e.packObject("files").let { f -> f.packKeys().map { f.getString(it) } } + listOfNotNull(e.opt("avatar_file") as? String).filter { it.isNotEmpty() }
            paths.distinct().forEach { path -> encoded.put(path, Base64.getEncoder().encodeToString(files.getValue(path))) }
            val metadata = JSONObject().put("format_version", 2).put("client_version", manifest.getString("client_version"))
                .put("resource", e).put("providers", providers).put("files", encoded)
            result["${e.packString("kind", "expert")}:${stored.resourceIds.getValue(e.getString("ref"))}"] = JSONObject().put("_xfpack", metadata)
        }
        val referenced = experts.flatMap { e -> listOfNotNull(e.optJSONObject("model")) + e.packObject("member_models").let { m -> m.packKeys().map { m.getJSONObject(it) } } }
        providers.objects().filter { it.packString("family", "text") == "text" }.forEach { p ->
            val ref = p.getString("ref")
            val models = (p.packArray("models").objects().mapNotNull { it.opt("id") as? String } + referenced.filter { it.getString("provider_ref") == ref }.map { it.getString("model_id") }).distinct()
            models.forEach { model ->
                val id = "pack-" + PortablePackCodec.sha256((packageId + "\u0000" + "$ref/$model").toByteArray()).take(40)
                result["model_profile:$id"] = JSONObject().put("_xfpack", JSONObject().put("provider", p).put("model_id", model))
            }
        }
    }
    store.marketResources.forEach { (id, raw) ->
        val kind = when { store.experts.any { it.id == id } -> "expert"; store.skills.any { it.id == id } -> "skill"; else -> null }
        if (kind != null) result.getOrPut("$kind:$id") { JSONObject() }.put("catalog", JSONObject(raw))
    }
    return result
}

/** Key-free preview, not an installer. Input uses sync entity envelopes, including deleted rows.
 * Keep packageId stable per account/space so an explicitly approved re-import keeps mobile IDs.
 * Unsupported single-member teams fail desktop validation; no members are invented or discarded.
 */
fun portablePackFromSync(entities: List<JSONObject>, packageId: String = "cloud-sync"): PortablePack {
    val live = entities.filterNot { it.optBoolean("deleted", false) }
    fun payload(e: JSONObject) = e.getJSONObject("payload")
    val configurations = live.filter { it.getString("entity_type") in setOf("expert", "team") }
    val refs = configurations.associate { e -> e.getString("entity_id") to (payload(e).optJSONObject("_xfpack")?.optJSONObject("resource")?.optString("ref")?.takeIf { it.isNotEmpty() } ?: "expert:" + PortablePackCodec.sha256(e.getString("entity_id").toByteArray()).take(32)) }
    require(refs.size == configurations.size) { "同步资源标识重复" }
    val providers = linkedMapOf<String, JSONObject>(); val assets = linkedMapOf<String, ByteArray>()
    val experts = JSONArray()
    fun asset(path: String, bytes: ByteArray) {
        PortablePackCodec.safePath(path)
        require(bytes.size <= PortablePackCodec.MAX_FILE && (assets[path] == null || assets.getValue(path).contentEquals(bytes))) { "同步包资产冲突或过大" }
        assets[path] = bytes
        require(assets.size < PortablePackCodec.MAX_FILES && assets.values.sumOf { it.size.toLong() } <= PortablePackCodec.MAX_TOTAL - PortablePackCodec.MAX_FILE)
    }
    configurations.forEach { entity ->
        val p = payload(entity); val meta = p.optJSONObject("_xfpack") ?: JSONObject()
        val resource = JSONObject((meta.optJSONObject("resource") ?: JSONObject()).toString())
        val ref = refs.getValue(entity.getString("entity_id"))
        resource.put("ref", ref).put("kind", entity.getString("entity_type")).put("name", p.getString("name")).put("system_prompt", p.packString("prompt", ""))
        if (entity.getString("entity_type") == "team") {
            val members = p.packArray("members").packStrings().map { refs[it] ?: error("同步团队缺少成员，无法导出") }
            resource.put("member_refs", JSONArray(members))
        }
        meta.packArray("providers").objects().forEach { descriptor ->
            val provider = JSONObject(descriptor.toString()).put("has_secret", false)
            val key = provider.getString("ref")
            val previous = providers[key]
            require(previous == null || portableCanonical(previous) == portableCanonical(provider)) { "同步提供商引用冲突" }
            providers[key] = provider
        }
        meta.packObject("files").let { files -> files.packKeys().forEach { path ->
            val encoded = files.packString(path)
            require(encoded.length <= (PortablePackCodec.MAX_FILE + 2) / 3 * 4)
            asset(path, Base64.getDecoder().decode(encoded))
        } }
        val mappings = resource.packObject("files")
        p.packArray("skills").packStrings().forEach { skillId ->
            val skill = live.firstOrNull { it.getString("entity_type") == "skill" && it.getString("entity_id") == skillId } ?: error("同步专家缺少技能，无法完整导出")
            val skillPayload = payload(skill)
            val existing = mappings.packKeys().firstOrNull { relative ->
                relative.startsWith("skills/") && relative.endsWith("/SKILL.md") &&
                    ("desktop-skill-" + PortablePackCodec.sha256((ref + ":" + relative).toByteArray()).take(24) == skillId ||
                        "pack-" + PortablePackCodec.sha256((packageId + "\u0000" + "$ref/$relative").toByteArray()).take(40) == skillId)
            }
            val relative = existing ?: "skills/sync-${PortablePackCodec.sha256(skillId.toByteArray()).take(24)}/SKILL.md"
            val path = mappings.optString(relative).takeIf { it.isNotEmpty() } ?: "assets/sync/${PortablePackCodec.sha256((ref + relative).toByteArray())}"
            // Skill text is an explicit edit to an existing file, not an archive collision.
            val bytes = skillPayload.getString("content").toByteArray()
            assets.remove(path); asset(path, bytes); mappings.put(relative, path)
        }
        val dependencies = resource.packArray("dependencies").packStrings().toMutableList()
        p.packArray("knowledge").packStrings().forEach { if ("knowledge:$it" !in dependencies) dependencies.add("knowledge:$it") }
        p.optJSONObject("catalog")?.let { catalog ->
            dependencies.addAll(catalog.packArray("dependencies").packStrings())
            val prefix = "skills/catalog-${PortablePackCodec.sha256(ref.toByteArray()).take(24)}/"
            val catalogFiles = catalog.packObject("files")
            catalogFiles.packKeys().forEach { file ->
                val relative = prefix + PortablePackCodec.safePath(file)
                val path = "assets/sync/${PortablePackCodec.sha256((ref + relative).toByteArray())}"
                asset(path, catalogFiles.packString(file).toByteArray()); mappings.put(relative, path)
            }
            val relative = "skills/catalog-metadata-${PortablePackCodec.sha256(ref.toByteArray()).take(24)}/source.json"
            val path = "assets/sync/${PortablePackCodec.sha256((ref + relative).toByteArray())}"
            asset(path, catalog.toString().toByteArray()); mappings.put(relative, path)
        }
        resource.put("dependencies", JSONArray(dependencies.distinct())).put("files", mappings)
        experts.put(resource)
    }
    live.filter { it.getString("entity_type") == "model_profile" }.forEach { entity ->
        val p = payload(entity); val meta = p.optJSONObject("_xfpack") ?: JSONObject()
        val fallback = JSONObject().put("ref", "text:" + PortablePackCodec.sha256(entity.getString("entity_id").toByteArray()).take(32))
        val descriptor = JSONObject((meta.optJSONObject("provider") ?: fallback).toString())
        val ref = descriptor.getString("ref")
        val models = providers[ref]?.packArray("models") ?: descriptor.packArray("models")
        val oldModel = meta.packString("model_id", p.getString("model"))
        val list = models.objects().toMutableList()
        list.find { it.optString("id") == oldModel }?.put("id", p.getString("model")) ?: list.add(JSONObject().put("id", p.getString("model")))
        descriptor.put("name", p.getString("name")).put("kind", descriptor.packString("kind", "openai")).put("base_url", p.getString("url"))
            .put("has_secret", false).put("models", JSONArray(list))
        providers[ref] = descriptor
    }
    require(experts.length() > 0 || providers.isNotEmpty()) { "所选同步资源不能单独表示为桌面资源包" }
    val manifest = JSONObject().put("package_id", packageId).put("client_version", "android-cloud-sync-v2").put("format_version", 2)
        .put("experts", experts).put("providers", JSONArray(providers.values.toList()))
    return PortablePackCodec.create(manifest, assets)
}

private fun portableCanonical(value: Any?): String = when (value) {
    is JSONObject -> value.packKeys().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + portableCanonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { portableCanonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
