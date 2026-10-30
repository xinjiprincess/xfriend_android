package com.singu.xfriend

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Full original metadata remains alongside the ordinary mobile entities. Assets are in a
 * validated inert archive; credentials are stored only in Store's encrypted preferences.
 */
data class PortableStoredPackage(val manifestJson: String, val resourceIds: Map<String, String>, val archiveName: String, val trustedSkillText: Boolean) {
    fun json(): JSONObject = JSONObject().put("manifest", JSONObject(manifestJson)).put("resourceIds", JSONObject(resourceIds)).put("archiveName", archiveName).put("trustedSkillText", trustedSkillText)
    companion object {
        fun read(j: JSONObject): PortableStoredPackage {
            val ids = j.getJSONObject("resourceIds")
            val file = j.getString("archiveName").also { require(Regex("[a-f0-9]{64}\\.xfpack").matches(it)) }
            return PortableStoredPackage(j.getJSONObject("manifest").toString(), ids.packKeys().associateWith { ids.getString(it) }, file, j.getBoolean("trustedSkillText"))
        }
    }
}

internal data class PortableMobileResources(val experts: List<Expert>, val teams: List<Team>, val skills: List<Skill>, val profiles: List<ModelProfile>, val ids: Map<String, String>)

internal fun portableSalesModelResolver(profiles: () -> List<ModelProfile>): PortableModelResolver = PortableModelResolver { packageId, ref, model ->
    val id = "pack-" + PortablePackCodec.sha256((packageId + "\u0000" + "$ref/$model").toByteArray()).take(40)
    profiles().find { it.id == id }?.takeIf { it.url.isNotBlank() }?.let { PortableRuntimeModel(it.url, it.model, it.key) }
}

internal fun portableMobileResources(plan: PortableInstallPlan): PortableMobileResources {
    val manifest = plan.manifest(); val packageId = manifest.getString("package_id")
    fun id(ref: String) = "pack-" + PortablePackCodec.sha256((packageId + "\u0000" + ref).toByteArray()).take(40)
    val rows = manifest.packArray("experts").objects()
    val ids = rows.associate { it.getString("ref") to id(it.getString("ref")) }
    val files = plan.files(); val skills = mutableListOf<Skill>()
    val experts = rows.filter { it.packString("kind", "expert") == "expert" }.map { e ->
        val ref = e.getString("ref"); val mappings = e.packObject("files")
        val selected = mappings.packKeys().filter { it.startsWith("skills/") && it.endsWith("/SKILL.md") }.sorted().map { path ->
            val skillId = id("$ref/$path")
            skills.add(Skill(skillId, path.split('/')[1], portableUtf8(files.getValue(mappings.getString(path)))))
            skillId
        }
        val prompt = if (!e.isNull("system_prompt") && e.has("system_prompt")) e.getString("system_prompt")
            else if (mappings.has("SOUL.md")) portableUtf8(files.getValue(mappings.getString("SOUL.md"))) else ""
        Expert(ids.getValue(ref), e.getString("name"), prompt, if (plan.trustSkills) selected else emptyList())
    }
    val teams = rows.filter { it.packString("kind", "expert") == "team" }.map { e -> Team(ids.getValue(e.getString("ref")), e.getString("name"), e.packArray("member_refs").packStrings().map { ids.getValue(it) }) }
    val referenced = rows.flatMap { e -> listOfNotNull(e.optJSONObject("model")) + e.packObject("member_models").let { m -> m.packKeys().map { m.getJSONObject(it) } } }
    val profiles = manifest.packArray("providers").objects().filter { it.packString("family", "text") == "text" }.flatMap { p ->
        val ref = p.getString("ref")
        val models = (p.packArray("models").objects().mapNotNull { (it.opt("id") as? String)?.takeIf(String::isNotEmpty) } + referenced.filter { it.getString("provider_ref") == ref }.map { it.getString("model_id") }).distinct()
        models.map { model -> ModelProfile(id("$ref/$model"), p.getString("name"), p.optString("base_url", "").takeUnless { it == "null" }.orEmpty(), model, plan.secrets()[ref].orEmpty()) }
    }
    return PortableMobileResources(experts, teams, skills, profiles, ids)
}

internal fun writePortableBytes(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val pending = File.createTempFile(".portable-", ".tmp", target.parentFile)
    try {
        pending.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { pending.delete() }
}

internal interface PortableInstallState {
    val workspaceRoot: File
    val portableRoot: File
    var experts: List<Expert>
    var teams: List<Team>
    var skills: List<Skill>
    var portablePackages: Map<String, PortableStoredPackage>
    var portableSecrets: Map<String, Map<String, String>>
    val marketResources: Map<String, String> get() = emptyMap()
    fun save()
}

internal class StorePortableInstallState(private val store: Store) : PortableInstallState {
    override val workspaceRoot get() = store.workspaceRoot
    override val portableRoot get() = store.portableRoot
    override var experts: List<Expert> get() = store.experts; set(value) { store.experts = value }
    override var teams: List<Team> get() = store.teams; set(value) { store.teams = value }
    override var skills: List<Skill> get() = store.skills; set(value) { store.skills = value }
    override var portablePackages: Map<String, PortableStoredPackage> get() = store.portablePackages; set(value) { store.portablePackages = value }
    override var portableSecrets: Map<String, Map<String, String>> get() = store.portableSecrets; set(value) { store.portableSecrets = value }
    override val marketResources get() = store.marketResources
    override fun save() = store.save()
}

internal fun installPortablePack(store: Store, plan: PortableInstallPlan, updateSales: ((SalesData) -> SalesData) -> SalesData): SalesData = synchronized(store) {
    installPortablePack(StorePortableInstallState(store), plan, updateSales)
}

/** Concrete all-or-rollback adapter. Invoke while the parent has paused use of this Store.
 * Sales writes use a transform so concurrently edited unrelated CRM rows are preserved.
 */
internal fun installPortablePack(store: PortableInstallState, plan: PortableInstallPlan, updateSales: ((SalesData) -> SalesData) -> SalesData): SalesData = synchronized(store) {
    val mobile = portableMobileResources(plan)
    val manifest = plan.manifest(); val packageId = manifest.getString("package_id")
    val archiveName = PortablePackCodec.sha256(packageId.toByteArray()) + ".xfpack"
    val oldExperts = store.experts; val oldTeams = store.teams; val oldSkills = store.skills
    val oldPackages = store.portablePackages; val oldSecrets = store.portableSecrets
    val undo = linkedMapOf<File, ByteArray?>()
    var oldProfiles: List<ModelProfile>? = null
    fun write(file: File, bytes: ByteArray) {
        require(!file.exists() || (file.isFile && file.length() <= PortablePackCodec.MAX_ARCHIVE)) { "目标文件不适合覆盖" }
        if (file !in undo) undo[file] = if (file.exists()) file.readBytes() else null
        writePortableBytes(file, bytes)
    }
    try {
        val assets = plan.files()
        manifest.packArray("experts").objects().forEach { e ->
            val root = workspacePath(store.workspaceRoot, mobile.ids.getValue(e.getString("ref")))
            val files = e.packObject("files")
            files.packKeys().forEach { relative -> write(workspacePath(root, PortablePackCodec.safePath(relative)), assets.getValue(files.getString(relative))) }
        }
        // Archive contains no provider secrets, even when credentials were explicitly imported.
        val publicManifest = JSONObject(manifest.toString())
        publicManifest.packArray("providers").objects().forEach { it.put("has_secret", false) }
        write(File(store.portableRoot, archiveName), PortablePackCodec.encode(PortablePackCodec.create(publicManifest, assets)))
        val incomingProfiles = mobile.profiles
        val next = updateSales { latest ->
            oldProfiles = latest.profiles
            val incoming = incomingProfiles.map { p -> if (plan.includeSecrets) p else p.copy(key = latest.profiles.find { it.id == p.id }?.key.orEmpty()) }
            latest.copy(profiles = latest.profiles.filterNot { p -> incoming.any { it.id == p.id } } + incoming)
        }
        store.experts = oldExperts.filterNot { e -> mobile.experts.any { it.id == e.id } } + mobile.experts
        store.teams = oldTeams.filterNot { e -> mobile.teams.any { it.id == e.id } } + mobile.teams
        store.skills = oldSkills.filterNot { e -> mobile.skills.any { it.id == e.id } } + mobile.skills
        store.portablePackages = oldPackages + (packageId to PortableStoredPackage(manifest.toString(), mobile.ids, archiveName, plan.trustSkills))
        if (plan.includeSecrets) store.portableSecrets = oldSecrets + (packageId to plan.secrets())
        store.save()
        next
    } catch (failure: Exception) {
        store.experts = oldExperts; store.teams = oldTeams; store.skills = oldSkills
        store.portablePackages = oldPackages; store.portableSecrets = oldSecrets
        try { store.save() } catch (rollback: Exception) { failure.addSuppressed(rollback) }
        oldProfiles?.let { old ->
            val affected = mobile.profiles.map { it.id }.toSet()
            try { updateSales { latest -> latest.copy(profiles = latest.profiles.filterNot { it.id in affected } + old.filter { it.id in affected }) } }
            catch (rollback: Exception) { failure.addSuppressed(rollback) }
        }
        undo.entries.toList().asReversed().forEach { (file, bytes) ->
            try { if (bytes == null) { check(!file.exists() || file.delete()) } else writePortableBytes(file, bytes) }
            catch (rollback: Exception) { failure.addSuppressed(rollback) }
        }
        throw failure
    }
}

/** Reciprocal export includes mobile prompt/name/member edits and workspace assets. */
fun exportInstalledPortablePack(store: Store, packageId: String, includeSecrets: Boolean = false, password: String = ""): ByteArray = synchronized(store) {
    exportInstalledPortablePack(StorePortableInstallState(store), packageId, includeSecrets, password)
}

internal fun exportInstalledPortablePack(store: PortableInstallState, packageId: String, includeSecrets: Boolean = false, password: String = ""): ByteArray = synchronized(store) {
    val installed = store.portablePackages.getValue(packageId)
    val original = PortablePackCodec.decode(File(store.portableRoot, installed.archiveName).readBytes())
    val manifest = JSONObject(installed.manifestJson)
    val assets = original.files().toMutableMap()
    manifest.packArray("experts").objects().forEach { e ->
        val ref = e.getString("ref"); val id = installed.resourceIds.getValue(ref)
        if (e.packString("kind", "expert") == "team") {
            val team = store.teams.find { it.id == id } ?: error("包内团队已删除，无法完整导出")
            val members = team.members.map { member -> installed.resourceIds.entries.firstOrNull { it.value == member }?.key ?: error("团队新增成员不在原包中，请先导出为新资源包") }
            e.put("name", team.name).put("member_refs", org.json.JSONArray(members))
            val assignments = e.packObject("member_models")
            assignments.packKeys().filterNot { it in members }.forEach { assignments.remove(it) }
        } else {
            val expert = store.experts.find { it.id == id } ?: error("包内专家已删除，无法完整导出")
            e.put("name", expert.name).put("system_prompt", expert.prompt)
        }
        val files = e.packObject("files")
        val root = workspacePath(store.workspaceRoot, id)
        files.packKeys().forEach { relative ->
            val file = workspacePath(root, PortablePackCodec.safePath(relative))
            require(file.isFile && file.length() <= PortablePackCodec.MAX_FILE) { "包内文件缺失或过大，无法完整导出" }
            // Each expert gets a distinct asset on export, even if the source archive shared one.
            val path = "assets/mobile/" + PortablePackCodec.sha256((ref + "\u0000" + relative).toByteArray())
            assets[path] = file.readBytes(); files.put(relative, path)
        }
        e.put("files", files)
    }
    val used = manifest.packArray("experts").objects().flatMap { e -> e.packObject("files").let { files -> files.packKeys().map { files.getString(it) } } + listOfNotNull(e.opt("avatar_file") as? String).filter { it.isNotEmpty() } }.toSet()
    assets.keys.filterNot { it in used }.forEach { assets.remove(it) }
    val secrets = if (includeSecrets) store.portableSecrets[packageId].orEmpty() else emptyMap()
    manifest.packArray("providers").objects().forEach { it.put("has_secret", it.getString("ref") in secrets) }
    PortablePackCodec.encode(PortablePackCodec.create(manifest, assets, secrets), password)
}
