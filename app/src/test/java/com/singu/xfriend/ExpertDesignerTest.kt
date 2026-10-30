package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExpertDesignerTest {
    private val draft = ExpertDraft("需求专家", "按原文提供需求证据。", "Markdown：结论、证据、待确认问题。")
    private fun rejects(text: String) { assertThrows(IllegalArgumentException::class.java) { ExpertDraft.parse(text) } }

    @Test fun parsesRealJsonAndFencedJson() {
        val json = draft.json().toString()
        assertEquals(draft, ExpertDraft.parse(json))
        assertEquals(draft, ExpertDraft.parse(" \n```json\n$json\n```\n"))
        assertEquals(draft, ExpertDraft.parse("```\r\n$json\r\n```"))
    }
    @Test fun parsesEscapedStringsAndUnicodeKeys() {
        val input = """{"\u006eame":"专家","prompt":"第一行\n\"引用\"\\路径","outputFormat":"表格"}"""
        assertEquals(ExpertDraft("专家", "第一行\n\"引用\"\\路径", "表格"), ExpertDraft.parse(input))
    }
    @Test fun rejectsLenientJsonExtensionsAndTrailingContent() {
        listOf(
            "{name:\"专家\",prompt:\"职责\",outputFormat:\"表格\"}",
            "{'name':'专家','prompt':'职责','outputFormat':'表格'}",
            """{"name":"专家","prompt":"职责","outputFormat":"表格",}""",
            """{"name":"专家";"prompt":"职责","outputFormat":"表格"}""",
            """{"name":"专家","prompt":/*comment*/"职责","outputFormat":"表格"}""",
            draft.json().toString() + "{}",
            "说明：" + draft.json(),
            "```json\n${draft.json()}\n```\n继续执行",
            "```json ${draft.json()}```",
            "{\"name\":\"专家\",\"prompt\":\"未转义\n换行\",\"outputFormat\":\"表格\"}"
        ).forEach(::rejects)
    }
    @Test fun rejectsMissingUnknownDuplicateAndNonStringFields() {
        listOf(
            "{}", """{"name":"专家","prompt":"职责"}""",
            """{"name":"专家","prompt":"职责","outputFormat":"表格","tools":[]}""",
            """{"name":"专家","prompt":"职责","prompt":"重复"}""",
            """{"name":"专家","prompt":"职责","outputFormat":12}""",
            """{"name":"专家","prompt":"职责","outputFormat":null}""",
            """{"name":"专家","prompt":"职责","outputFormat":{}}""",
            """{"name":"专家","prompt":"职责","outputFormat":["表格"]}"""
        ).forEach(::rejects)
    }
    @Test fun rejectsInvalidEscapesAndEscapedDuplicateKeys() {
        listOf(
            """{"name":"专家","prompt":"职责","outputFormat":"\x20"}""",
            """{"name":"专家","prompt":"职责","outputFormat":"\u12xz"}""",
            """{"name":"专家","prompt":"职责","\u0070rompt":"重复"}"""
        ).forEach(::rejects)
    }
    @Test fun validatesEditedDraftsAndByteLimit() {
        listOf(draft.copy(name = " "), draft.copy(name = "a".repeat(101)), draft.copy(prompt = ""), draft.copy(prompt = "a".repeat(3201)), draft.copy(outputFormat = "a".repeat(1001))).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validated() }
            rejects(it.json().toString())
        }
        rejects("中".repeat(33000))
        assertEquals(3200, ExpertDraft.parse(draft.copy(prompt = "a".repeat(3200)).json().toString()).prompt.length)
    }
    @Test fun stableSingleRoleAnalyzeRequestUsesAllInputs() {
        val first = designerTrialBody("design", DESIGNER_PROMPT, "客户需求", "严格 JSON")
        assertEquals(first.toString(), designerTrialBody("design", DESIGNER_PROMPT, "客户需求", "严格 JSON").toString())
        assertEquals("analyze", first.getString("operation")); assertEquals("text", first.getString("kind")); assertEquals("", first.getString("media"))
        assertEquals(1, first.getJSONArray("roles").length())
        val id = first.getString("request_id")
        assertTrue(id.matches(Regex("[a-zA-Z0-9:-]{1,150}")))
        listOf(designerTrialBody("test", DESIGNER_PROMPT, "客户需求", "严格 JSON"), designerTrialBody("design", DESIGNER_PROMPT, "修改需求", "严格 JSON"), designerTrialBody("design", DESIGNER_PROMPT, "客户需求", "修改角色")).forEach { assertNotEquals(id, it.getString("request_id")) }
        assertFalse(first.has("tools")); assertFalse(first.has("tool_choice"))
    }
    @Test fun revisionInputContainsEditableDraft() {
        val input = JSONObject(expertDesignInput("分析需求", "增加证据", draft))
        assertEquals(draft.json().toString(), input.getJSONObject("draft").toString())
        assertEquals("增加证据", input.getString("revision"))
        assertThrows(IllegalArgumentException::class.java) { expertDesignInput("", "", null) }
    }
    @Test fun repeatedSaveDoesNotCreateDuplicateOrVersion() {
        val first = designedExpertPlan(emptyList(), draft)
        val repeat = designedExpertPlan(listOf(first.expert), draft)
        assertEquals(first.expert.id, repeat.expert.id); assertTrue(repeat.unchanged); assertNull(repeat.previous)
        assertEquals(first.expert.id, designedExpertPlan(emptyList(), draft).expert.id)
    }
    @Test fun stableIdCollisionArchivesEditedExpertInsteadOfOverwritingSilently() {
        val initial = designedExpertPlan(emptyList(), draft).expert
        val edited = initial.copy(name = "后来编辑", prompt = "新职责", skills = listOf("retained"))
        val plan = designedExpertPlan(listOf(edited), draft)
        assertEquals(initial.id, plan.expert.id); assertEquals(edited.skills, plan.expert.skills)
        assertEquals(edited.prompt, plan.previous!!.prompt)
    }
    @Test fun updatePreservesReferencesAndArchivesOriginalPrompt() {
        val old = Expert("existing", "旧专家", "旧职责", listOf("skill"), listOf("knowledge"))
        val plan = designedExpertPlan(listOf(old), draft, old.id)
        assertEquals(old.id, plan.expert.id); assertEquals(old.skills, plan.expert.skills); assertEquals(old.knowledge, plan.expert.knowledge)
        assertEquals(old.prompt, plan.previous!!.prompt); assertEquals(old.skills, plan.previous.skills)
        assertTrue(plan.expert.prompt.contains(draft.outputFormat))
        assertEquals(plan.previous, ExpertRevision.read(plan.previous.json()))
        assertThrows(IllegalStateException::class.java) { designedExpertPlan(emptyList(), draft, "deleted") }
    }
    @Test fun outputFormatSurvivesAnalysisRoleLengthLimit() {
        val maximal = ExpertDraft("名".repeat(100), "职".repeat(3200), "格".repeat(1000))
        val role = analysisRolePrompt(designedExpertPlan(emptyList(), maximal).expert)
        assertTrue(role.length <= 4500); assertTrue(role.endsWith(maximal.outputFormat))
        val testRole = maximal.name + "：" + maximal.rolePrompt()
        assertTrue(testRole.length <= 4500)
        assertEquals(testRole, designerTrialBody("test", "文本测试", "样例", testRole).getJSONArray("roles").getString(0))
    }
    @Test fun failedArchiveDoesNotChangeExpertLibrary() {
        val old = listOf(Expert("existing", "旧专家", "旧职责")); var experts = old; var writes = 0
        assertThrows(IllegalStateException::class.java) {
            commitDesignedExpert(SalesData(), designedExpertPlan(old, draft, "existing"), old, { experts = it }, { writes++ }, { error("disk full") })
        }
        assertEquals(old, experts); assertEquals(0, writes)
    }
    @Test fun failedExpertWriteRestoresLibraryAndArchive() {
        val data = SalesData(); val old = listOf(Expert("existing", "旧专家", "旧职责")); var experts = old; var persisted = data; val snapshots = mutableListOf<SalesData>()
        assertThrows(IllegalStateException::class.java) {
            commitDesignedExpert(data, designedExpertPlan(old, draft, "existing"), old, { experts = it }, { error("disk full") }, { persisted = it; snapshots.add(it) })
        }
        assertEquals(old, experts); assertEquals(data, persisted); assertEquals(1, snapshots.first().expertVersions.size); assertEquals(2, snapshots.size)
    }
    @Test fun commitSavesVersionBeforeExpertAndRetainsOtherData() {
        val old = listOf(Expert("existing", "旧专家", "旧职责")); var experts = old; val data = SalesData(customers = listOf(Customer(name = "客户")))
        var archived = false
        val next = commitDesignedExpert(data, designedExpertPlan(old, draft, "existing"), old, { experts = it }, { assertTrue(archived) }, { archived = true })
        assertEquals(data.customers, next.customers); assertEquals(1, next.expertVersions.size); assertEquals(draft.rolePrompt(), experts.single().prompt)
        var writes = 0
        assertEquals(next, commitDesignedExpert(next, designedExpertPlan(experts, draft, "existing"), experts, { error("must not mutate") }, { writes++ }, { writes++ }))
        assertEquals(0, writes)
    }
}
