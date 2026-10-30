package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FriendBlueprintSkill(val slug: String, val prompt: String)
internal data class FriendBlueprintExpert(val ref: String, val name: String, val description: String, val prompt: String, val model: String?, val skills: List<FriendBlueprintSkill>)
internal data class FriendBlueprintTeam(val ref: String, val name: String, val description: String, val prompt: String, val model: String?, val members: List<String>, val memberModels: Map<String, String>)

/** Inert v1 data. Parsing and planning never acquire a Store or write files. */
internal data class FriendBlueprint(val experts: List<FriendBlueprintExpert>, val teams: List<FriendBlueprintTeam>) {
    val requestedModels: List<String> get() = (experts.mapNotNull { it.model } + teams.flatMap { listOfNotNull(it.model) + it.memberModels.values }).distinct()

    companion object {
        const val MAX_BYTES = 1024 * 1024

        fun parse(raw: String): FriendBlueprint {
            require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "蓝图不能超过 1 MiB" }
            var text = raw.trim { it in " \t\r\n" }
            if (text.startsWith("```")) {
                val fenced = Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*)\\r?\\n```\\z").matchEntire(text)
                require(fenced != null) { "仅接受 JSON 对象或单个 json 代码块" }
                text = fenced.groupValues[1]
            }
            val root = try { portableJson(text.toByteArray(Charsets.UTF_8)) }
                catch (_: Exception) { throw IllegalArgumentException("蓝图不是严格 JSON，或存在重复键") }
            root.fields(setOf("format", "version", "experts", "teams"), "蓝图")
            require(root.opt("format") == "xfriend-agent-blueprint") { "蓝图 format 不受支持" }
            val version = root.opt("version")
            require(version is Number && version.toDouble() == 1.0) { "仅支持蓝图 v1" }
            val experts = root.array("experts", 1, 30, required = true).map { value ->
                val e = value as? JSONObject ?: errorInput("experts 必须为对象数组")
                e.fields(setOf("ref", "name", "description", "prompt", "model", "skills"), "专家")
                val skills = e.array("skills", 0, 20).map { item ->
                    val s = item as? JSONObject ?: errorInput("skills 必须为对象数组")
                    s.fields(setOf("slug", "prompt"), "技能")
                    FriendBlueprintSkill(s.ref("slug"), s.string("prompt", 1, 50000))
                }
                require(skills.map { it.slug }.distinct().size == skills.size) { "同一专家的技能 slug 重复" }
                FriendBlueprintExpert(e.ref("ref"), e.string("name", 1, 100), e.string("description", 0, 20000, ""), e.string("prompt", 1, 100000), e.model(), skills)
            }
            val teams = root.array("teams", 0, 10).map { value ->
                val t = value as? JSONObject ?: errorInput("teams 必须为对象数组")
                t.fields(setOf("ref", "name", "description", "prompt", "model", "members", "member_models"), "团队")
                val members = t.array("members", 2, 20, required = true).map { it as? String ?: errorInput("成员 ref 必须为字符串") }
                require(members.distinct().size == members.size) { "团队成员重复" }
                require(members.all { member -> experts.any { it.ref == member } }) { "团队成员必须引用本蓝图中的专家，不能引用团队或未知 ref" }
                val assignments = if (t.has("member_models")) t.opt("member_models") as? JSONObject ?: errorInput("member_models 必须为对象") else JSONObject()
                val models = assignments.keys().asSequence().associateWith { member ->
                    require(member in members) { "member_models 引用了非成员" }
                    assignments.string(member, 0, Int.MAX_VALUE)
                }
                FriendBlueprintTeam(t.ref("ref"), t.string("name", 1, 100), t.string("description", 0, 20000, ""), t.string("prompt", 1, 100000), t.model(), members, models)
            }
            val refs = experts.map { it.ref } + teams.map { it.ref }
            require(refs.distinct().size == refs.size) { "专家与团队 ref 必须全局唯一" }
            return FriendBlueprint(experts, teams)
        }
    }
}

private fun errorInput(message: String): Nothing = throw IllegalArgumentException(message)
private fun JSONObject.fields(allowed: Set<String>, location: String) {
    require(keys().asSequence().all { it in allowed }) { "$location 存在不支持的字段；不允许命令、工具配置、文件路径或凭据" }
}
private fun JSONObject.string(key: String, min: Int, max: Int, default: String? = null): String {
    if (!has(key) && default != null) return default
    val value = opt(key) as? String ?: errorInput("$key 必须为字符串")
    require(value.codePointCount(0, value.length) in min..max) { "$key 长度必须为 $min–$max 字" }
    return value
}
private fun JSONObject.ref(key: String): String = string(key, 1, 80).also {
    require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "$key 仅允许字母、数字、下划线和短横线" }
}
private fun JSONObject.model(): String? {
    if (!has("model") || isNull("model")) return null
    return string("model", 0, 400)
}
private fun JSONObject.array(key: String, min: Int, max: Int, required: Boolean = false): List<Any> {
    if (!has(key) && !required) return emptyList()
    val array = opt(key) as? JSONArray ?: errorInput("$key 必须为数组")
    require(array.length() in min..max) { "$key 数量必须为 $min–$max" }
    return (0 until array.length()).map { array.get(it) }
}

internal data class FriendBlueprintModel(val baseUrl: String, val model: String, val configured: Boolean)
internal data class FriendBlueprintPlan(
    val accountScope: String,
    val generation: Long,
    val model: FriendBlueprintModel,
    val experts: List<Expert>,
    val teams: List<Team>,
    val skills: List<Skill>,
    val notices: List<String>,
) {
    fun validateCommit(scope: String, epoch: Long, currentModel: FriendBlueprintModel, acknowledged: Boolean) {
        require(scope == accountScope && epoch == generation) { "账号已切换，请重新预览" }
        require(model == currentModel && currentModel.configured) { "手机模型配置已变化，请重新预览" }
        require(acknowledged) { "请确认模型映射、技能内容及兼容性说明" }
    }
}

/** Explicit mappings are necessary even for equal model IDs: a desktop provider is not a phone provider. */
internal fun FriendBlueprint.plan(scope: String, generation: Long, phone: FriendBlueprintModel, mappings: Map<String, String>): FriendBlueprintPlan {
    require(scope.matches(Regex("[a-f0-9]{64}"))) { "请先登录手机账号" }
    require(teams.all { it.members.size <= 8 }) { "手机运行器每个团队最多支持 8 位成员；请拆分超过 8 位成员的团队后重新导入，不会自动删减成员。" }
    require(phone.configured && phone.baseUrl.isNotBlank() && phone.model.isNotBlank()) { "请先配置手机可用模型" }
    require(mappings.keys == requestedModels.toSet() && mappings.values.all { it == phone.model }) { "请为每个蓝图模型明确选择手机模型" }
    val ids = (experts.map { it.ref } + teams.map { it.ref }).associateWith { "blueprint-${UUID.randomUUID()}" }
    val skills = mutableListOf<Skill>()
    val mobileExperts = experts.map { e ->
        val selected = e.skills.map { s ->
            val skill = Skill("blueprint-${UUID.randomUUID()}", s.slug, s.prompt)
            skills.add(skill); skill.id
        }
        Expert(ids.getValue(e.ref), e.name, e.prompt, selected)
    }
    val notices = buildList {
        add("所有导入资源使用手机当前模型 ${phone.model}（${phone.baseUrl}）；以后更改手机默认模型也会影响这些资源，不保存独立模型绑定。")
        requestedModels.forEach { add("模型映射：$it → ${phone.model}") }
        experts.filter { it.description.isNotEmpty() }.forEach { add("专家 ${it.name} 的 description 不会保存：${it.description}") }
        teams.forEach {
            if (it.description.isNotEmpty()) add("团队 ${it.name} 的 description 不会保存：${it.description}")
            add("团队 ${it.name} 的主持 prompt 不会保存，运行使用手机内置团队流程：${it.prompt}")
            if (it.memberModels.isNotEmpty()) add("团队 ${it.name} 不保存 member_models；所有成员使用上面确认的手机模型。")
        }
        if (skills.isNotEmpty()) add("技能将保存并绑定到对应专家，后续对话会使用这些提示词；请核对内容。导入预览不执行任何命令。")
    }
    return FriendBlueprintPlan(scope, generation, phone, mobileExperts, teams.map { Team(ids.getValue(it.ref), it.name, it.members.map(ids::getValue)) }, skills.toList(), notices)
}
