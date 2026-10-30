package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Validated, inert desktop document. Copies prevent a preview caller mutating validated data.
 * Secrets must be persisted separately in account-scoped encrypted storage by the installer.
 */
class PortablePack internal constructor(manifest: JSONObject, files: Map<String, ByteArray>, secrets: Map<String, String>) {
    private val document = manifest.toString()
    private val assets = files.mapValues { it.value.copyOf() }
    private val credentials = secrets.toMap()
    fun manifest(): JSONObject = JSONObject(document)
    fun files(): Map<String, ByteArray> = assets.mapValues { it.value.copyOf() }
    fun secrets(): Map<String, String> = credentials.toMap()
    override fun toString(): String = "PortablePack(assets=${assets.size}, secrets=${credentials.size})"
}

class InvalidPortablePack : IllegalArgumentException("导入包无效、不受支持或密码不正确")

internal fun JSONObject.packKeys(): Set<String> = keys().asSequence().toSet()
internal fun JSONObject.packObject(key: String): JSONObject = if (has(key)) getJSONObject(key) else JSONObject()
internal fun JSONObject.packArray(key: String): JSONArray = if (has(key)) getJSONArray(key) else JSONArray()
internal fun JSONArray.packStrings(): List<String> = (0 until length()).map { get(it).also { v -> require(v is String) } as String }
internal fun JSONObject.packString(key: String, default: String? = null): String {
    if (!has(key) && default != null) return default
    return get(key).also { require(it is String) } as String
}
private fun JSONObject.fields(vararg allowed: String) { require(packKeys().all { it in allowed }) }
private fun JSONObject.string(key: String, min: Int = 0, max: Int = Int.MAX_VALUE, optional: Boolean = false) {
    if (optional && (!has(key) || isNull(key))) return
    val s = packString(key)
    require(s.codePointCount(0, s.length) in min..max)
}
private fun JSONObject.strings(key: String, max: Int): List<String> = packArray(key).packStrings().also { require(it.size <= max) }
private fun JSONObject.enum(key: String, default: String, vararg values: String): String = packString(key, default).also { require(it in values) }

internal fun validatePortableManifest(j: JSONObject) {
    j.fields("format", "format_version", "package_id", "client_version", "providers", "experts", "checksums", "warnings")
    require(j.packString("format", "xfriend-portable") == "xfriend-portable")
    val version = if (j.has("format_version")) j.get("format_version") else 2
    require(version is Number && version.toDouble() in listOf(1.0, 2.0))
    j.string("package_id"); j.string("client_version"); j.strings("warnings", Int.MAX_VALUE)
    j.packObject("checksums").let { hashes -> hashes.packKeys().forEach { hashes.string(it) } }
    val providers = j.packArray("providers")
    val experts = j.packArray("experts")
    require(providers.length() <= 1000 && experts.length() <= 2000)
    val refs = mutableSetOf<String>()
    fun ref(o: JSONObject): String = o.packString("ref").also {
        require(Regex("[a-z]+:[a-zA-Z0-9_-]{1,100}").matches(it) && refs.add(it))
    }
    val ps = (0 until providers.length()).associate { index ->
        val p = providers.getJSONObject(index)
        p.fields("ref", "name", "family", "kind", "capability", "base_url", "models", "extra", "has_secret")
        p.string("name", 1, 100); p.string("kind", 1, 80)
        p.enum("family", "text", "text", "voice"); if (p.has("capability")) p.string("capability")
        p.string("base_url", max = 2000, optional = true)
        if (p.has("base_url") && !p.isNull("base_url") && p.getString("base_url").isNotEmpty()) {
            val uri = URI(p.getString("base_url"))
            require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrEmpty())
            require(uri.userInfo.isNullOrEmpty() && uri.query.isNullOrEmpty() && uri.fragment.isNullOrEmpty())
        }
        val models = p.packArray("models"); require(models.length() <= 1000)
        repeat(models.length()) { models.getJSONObject(it) }
        p.packObject("extra")
        if (p.has("has_secret")) require(p.get("has_secret") is Boolean)
        ref(p) to p
    }
    fun model(m: JSONObject) {
        m.fields("provider_ref", "model_id"); m.string("provider_ref"); m.string("model_id", 1, 300)
        require(ps[m.getString("provider_ref")]?.packString("family", "text") == "text")
    }
    val es = (0 until experts.length()).associate { index ->
        val e = experts.getJSONObject(index)
        e.fields("ref", "name", "kind", "description", "system_prompt", "welcome_message", "persona_mbti", "color", "icon_name", "bundled_icon", "avatar_file", "model", "preferences", "engine", "member_refs", "member_models", "files", "dependencies")
        e.string("name", 1, 100); e.enum("kind", "expert", "expert", "team"); e.enum("engine", "native", "native", "dsh")
        mapOf("description" to 20000, "system_prompt" to 200000, "welcome_message" to 20000, "persona_mbti" to 4, "color" to 50, "icon_name" to 80, "bundled_icon" to Int.MAX_VALUE, "avatar_file" to Int.MAX_VALUE).forEach { (key, max) -> e.string(key, max = max, optional = true) }
        if (e.has("bundled_icon") && !e.isNull("bundled_icon")) require(e.getString("bundled_icon").let { it.isEmpty() || Regex("/experts/avatars/[a-zA-Z0-9_-]+\\.svg").matches(it) })
        if (e.has("model") && !e.isNull("model")) model(e.getJSONObject("model"))
        val prefs = e.packObject("preferences")
        prefs.fields("max_iters", "max_input_length", "max_tokens", "temperature", "top_p", "tools_disabled")
        mapOf("max_iters" to 1000, "max_input_length" to 10000000, "max_tokens" to 10000000, "temperature" to 2, "top_p" to 1).forEach { (key, max) ->
            if (prefs.has(key) && !prefs.isNull(key)) {
                val v = prefs.get(key); require(v is Number)
                val n = v.toDouble(); val integer = key.startsWith("max_")
                require(n.isFinite() && n >= (if (integer) 1 else 0) && n <= max && (!integer || n % 1 == 0.0))
            }
        }
        prefs.strings("tools_disabled", 500)
        val members = e.strings("member_refs", 500)
        e.strings("dependencies", 1000)
        val assignments = e.packObject("member_models")
        require(version.toInt() != 1 || assignments.length() == 0)
        assignments.packKeys().forEach { require(it in members); model(assignments.getJSONObject(it)) }
        e.packObject("files").let { files -> files.packKeys().forEach { files.string(it) } }
        ref(e) to e
    }
    es.values.forEach { e ->
        val members = e.packArray("member_refs").packStrings()
        if (e.packString("kind", "expert") == "team") {
            require(members.size >= 2 && members.distinct().size == members.size)
            require(members.all { es[it]?.packString("kind", "expert") == "expert" })
        } else require(members.isEmpty())
    }
}
