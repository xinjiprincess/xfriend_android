package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FriendBlueprintTest {
    private val scope = "a".repeat(64)
    private val phone = FriendBlueprintModel("https://phone.example/v1", "phone-model", true)
    private fun expert(ref: String) = JSONObject().put("ref", ref).put("name", "专家 $ref").put("prompt", "分析证据")
    private fun sample() = JSONObject().put("format", "xfriend-agent-blueprint").put("version", 1)
        .put("experts", JSONArray(listOf(expert("a"), expert("b"))))
        .put("teams", JSONArray().put(JSONObject().put("ref", "team").put("name", "团队").put("prompt", "主持讨论").put("members", JSONArray(listOf("a", "b")))))
    private fun rejects(text: String) { assertThrows(IllegalArgumentException::class.java) { FriendBlueprint.parse(text) } }
    private fun rejects(root: JSONObject) = rejects(root.toString())

    @Test fun parsesExampleAndFencedJsonWithoutChangingSource() {
        val source = sample().toString()
        val parsed = FriendBlueprint.parse(source)
        assertEquals(parsed, FriendBlueprint.parse("```json\r\n$source\r\n```"))
        assertEquals(listOf("a", "b"), parsed.teams.single().members)
        val plan = parsed.plan(scope, 2, phone, emptyMap())
        assertEquals(source, sample().toString())
        assertEquals(plan.experts.map { it.id }, plan.teams.single().members)
        assertTrue(plan.notices.any { "主持 prompt 不会保存" in it })
    }

    @Test fun rejectsDuplicateKeysEvenEscapedAndLenientJson() {
        val valid = sample().toString()
        listOf(
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"ref\":\"a\"", "\"ref\":\"a\",\"ref\":\"c\""),
            valid + "{}", valid.replace("\"version\":1", "\"version\":01"),
            valid.replace("\"version\":1", "\"version\":true"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":2"),
            "{'format':'xfriend-agent-blueprint'}", "{\"format\":/*comment*/1}",
            "```json\n$valid\n``` trailing", "[${valid}]"
        ).forEach(::rejects)
    }

    @Test fun rejectsCommandsCredentialsUnknownFieldsAtEveryLevel() {
        listOf("command", "api_key", "credentials", "files", "tools", "base_url").forEach { key ->
            rejects(sample().put(key, "forbidden"))
            rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put(key, "forbidden") })
            rejects(sample().also { it.getJSONArray("teams").getJSONObject(0).put(key, "forbidden") })
            rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("skills", JSONArray().put(JSONObject().put("slug", "s").put("prompt", "text").put(key, "forbidden"))) })
        }
    }

    @Test fun rejectsRefsMembersAndDuplicateSkills() {
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(1).put("ref", "a") })
        rejects(sample().also { it.getJSONArray("teams").getJSONObject(0).put("ref", "a") })
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("ref", "../a") })
        listOf(listOf("a"), listOf("a", "a"), listOf("a", "unknown"), listOf("a", "team")).forEach { members ->
            rejects(sample().also { it.getJSONArray("teams").getJSONObject(0).put("members", JSONArray(members)) })
        }
        rejects(sample().also { it.getJSONArray("teams").getJSONObject(0).put("member_models", JSONObject().put("other", "model")) })
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("skills", JSONArray().put(JSONObject().put("slug", "s").put("prompt", "x")).put(JSONObject().put("slug", "s").put("prompt", "y"))) })
    }

    @Test fun enforcesSizesAndTypes() {
        rejects(" ".repeat(FriendBlueprint.MAX_BYTES + 1))
        rejects("汉".repeat(FriendBlueprint.MAX_BYTES / 3 + 1))
        rejects(sample().put("experts", JSONArray()))
        rejects(sample().put("experts", JSONArray((0..30).map { expert("e$it") })))
        rejects(sample().put("teams", JSONObject()))
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("skills", JSONObject.NULL) })
        listOf("name" to "x".repeat(101), "prompt" to "x".repeat(100001), "description" to "x".repeat(20001), "model" to "x".repeat(401)).forEach { (field, value) ->
            rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put(field, value) })
        }
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("prompt", 123) })
        val unicode = sample().also { it.getJSONArray("experts").getJSONObject(0).put("name", "😀".repeat(100)) }
        assertEquals(200, FriendBlueprint.parse(unicode.toString()).experts.first().name.length)
    }

    @Test fun requiresExplicitModelMappingIncludingProviderIdentity() {
        val parsed = FriendBlueprint.parse(sample().also {
            it.getJSONArray("experts").getJSONObject(0).put("model", "desktop/provider/model")
            it.getJSONArray("teams").getJSONObject(0).put("member_models", JSONObject().put("b", "phone-model"))
        }.toString())
        assertThrows(IllegalArgumentException::class.java) { parsed.plan(scope, 1, phone, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { parsed.plan(scope, 1, phone, parsed.requestedModels.associateWith { "other" }) }
        val plan = parsed.plan(scope, 1, phone, parsed.requestedModels.associateWith { phone.model })
        assertTrue(plan.notices.any { "desktop/provider/model → phone-model" in it })
        assertTrue(plan.notices.any { "不保存 member_models" in it })
        assertThrows(IllegalArgumentException::class.java) { parsed.plan(scope, 1, phone.copy(configured = false), parsed.requestedModels.associateWith { phone.model }) }
    }

    @Test fun preservesSkillBindingsAndDisclosesUnsupportedFields() {
        val source = sample().also { root -> root.getJSONArray("experts").getJSONObject(0)
            .put("description", "说明").put("skills", JSONArray().put(JSONObject().put("slug", "research").put("prompt", "只引用证据"))) }
        val plan = FriendBlueprint.parse(source.toString()).plan(scope, 1, phone, emptyMap())
        assertEquals("只引用证据", plan.skills.single().content)
        assertEquals(listOf(plan.skills.single().id), plan.experts.first().skills)
        assertTrue(plan.experts.last().skills.isEmpty())
        assertTrue(plan.notices.any { "description 不会保存：说明" in it })
    }

    @Test fun enforcesNestedCollectionAndSkillLimits() {
        val skill = JSONObject().put("slug", "s").put("prompt", "text")
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("skills", JSONArray((0..20).map { n -> JSONObject(skill.toString()).put("slug", "s$n") })) })
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("skills", JSONArray().put(JSONObject(skill.toString()).put("prompt", "x".repeat(50001)))) })
        rejects(sample().also { it.getJSONArray("experts").getJSONObject(0).put("skills", JSONArray().put(JSONObject(skill.toString()).put("slug", "../s"))) })
        rejects(sample().also { root -> root.put("teams", JSONArray((0..10).map { n -> JSONObject(root.getJSONArray("teams").getJSONObject(0).toString()).put("ref", "t$n") })) })
        rejects(sample().also { it.getJSONArray("teams").getJSONObject(0).put("members", JSONArray((0..20).map { "a" })) })
        rejects(sample().also { it.getJSONArray("teams").getJSONObject(0).put("member_models", JSONObject().put("a", JSONObject.NULL)) })
    }

    @Test fun optionalDefaultsAndEmptyModelStillRequireExplicitMapping() {
        val root = JSONObject().put("format", "xfriend-agent-blueprint").put("version", 1)
            .put("experts", JSONArray().put(expert("a").put("model", "")))
        val parsed = FriendBlueprint.parse(root.toString())
        assertTrue(parsed.teams.isEmpty())
        assertEquals("", parsed.experts.single().description)
        assertThrows(IllegalArgumentException::class.java) { parsed.plan(scope, 1, phone, emptyMap()) }
        assertEquals(1, parsed.plan(scope, 1, phone, mapOf("" to phone.model)).experts.size)
        root.getJSONArray("experts").getJSONObject(0).put("model", JSONObject.NULL)
        assertTrue(FriendBlueprint.parse(root.toString()).requestedModels.isEmpty())
    }

    @Test fun phonePlanRejectsLargeV1TeamsWithoutDroppingMembers() {
        fun blueprint(count: Int): FriendBlueprint {
            val refs = (1..count).map { "expert$it" }
            val root = sample().put("experts", JSONArray(refs.map(::expert)))
            root.getJSONArray("teams").getJSONObject(0).put("members", JSONArray(refs))
            return FriendBlueprint.parse(root.toString())
        }
        val supported = blueprint(8).plan(scope, 1, phone, emptyMap())
        assertEquals(8, supported.teams.single().members.size)
        listOf(9, 20).forEach { count ->
            val parsed = blueprint(count)
            assertEquals(count, parsed.teams.single().members.size)
            val failure = assertThrows(IllegalArgumentException::class.java) { parsed.plan(scope, 1, phone, emptyMap()) }
            assertTrue(failure.message.orEmpty().contains("最多支持 8 位成员"))
            assertEquals(count, parsed.teams.single().members.size)
        }
    }

    @Test fun rejectsAccountSwitchLogoutReloginModelChangesAndMissingConsent() {
        val plan = FriendBlueprint.parse(sample().toString()).plan(scope, 8, phone, emptyMap())
        plan.validateCommit(scope, 8, phone, true)
        assertThrows(IllegalArgumentException::class.java) { plan.validateCommit("b".repeat(64), 8, phone, true) }
        assertThrows(IllegalArgumentException::class.java) { plan.validateCommit(scope, 9, phone, true) }
        assertThrows(IllegalArgumentException::class.java) { plan.validateCommit(scope, 8, phone.copy(model = "new"), true) }
        assertThrows(IllegalArgumentException::class.java) { plan.validateCommit(scope, 8, phone.copy(baseUrl = "https://other.example"), true) }
        assertThrows(IllegalArgumentException::class.java) { plan.validateCommit(scope, 8, phone.copy(configured = false), true) }
        assertThrows(IllegalArgumentException::class.java) { plan.validateCommit(scope, 8, phone, false) }
    }
}
