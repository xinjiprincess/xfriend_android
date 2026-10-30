package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CustomerCrmTest {
    private val portrait = CustomerInsight("采购负责人", "邮件沟通", "降低维护成本", "十万元", "四季度", "审批人", "A")
    private val customer = Customer(id = "c", name = "客户", insight = portrait)
    private val record = Communication(id = "r", title = "拜访", customerId = "c", created = 100L,
        attachments = listOf("image.jpg", "audio.wav"), rawTranscript = "原始转写", analysisJson = """{"profile":{"needs":"降低维护成本"},"actions":[{"title":"发送报价","dueDate":"2028-02-29","dueTime":"09:30","priority":"高"}],"evidence":["预算十万元"],"probability":{"low":30,"high":60,"reason":"预算已确认，时间待核实"}}""")

    @Test fun legacyEntitiesReadSafeDefaults() {
        assertEquals(CustomerInsight(), Customer.read(JSONObject("""{"id":"old","name":"旧客户"}""")).insight)
        val legacy = Communication.read(JSONObject("""{"id":"old","title":"旧沟通","created":1}"""))
        assertTrue(legacy.attachments.isEmpty()); assertEquals("", legacy.rawTranscript); assertEquals("", legacy.analysisJson)
        val task = FollowUp.read(JSONObject("""{"id":"old","title":"回访"}"""))
        assertEquals("普通", task.priority); assertEquals("", task.dueTime); assertFalse(task.reminder)
        val opportunity = Opportunity.read(JSONObject("""{"id":"old","name":"旧商机"}"""))
        assertEquals("", opportunity.probabilityLow); assertEquals("", opportunity.probabilityEvidence)
        assertFalse(ModelProfile.read(JSONObject("""{"id":"p","url":"https://example.org","model":"m"}""")).syncSecret)
    }

    @Test fun allCrmFieldsSurviveJsonRoundTrip() {
        val original = SalesData(customers = listOf(customer), records = listOf(record),
            profiles = listOf(ModelProfile(id = "p", syncSecret = true)),
            followUps = listOf(FollowUp(id = "f", title = "报价", customerId = "c", recordId = "r", dueDate = "2028-02-29", priority = "高", dueTime = "09:30", reminder = true)),
            opportunities = listOf(Opportunity(id = "o", title = "增购", customerId = "c", probabilityLow = "30", probabilityHigh = "60", probabilityReason = "预算确认", probabilityEvidence = "预算十万元")))
        assertEquals(original, SalesData.read(JSONObject(original.json().toString())))
    }

    @Test fun concurrentAnalysisAndManualEditsPreserveEveryNewRecordField() {
        val before = SalesData(customers = listOf(customer), records = listOf(record))
        val ui = before.copy(records = listOf(record.copy(title = "新标题", attachments = record.attachments + "more.jpg")))
        val worker = before.copy(records = listOf(record.copy(rawTranscript = "后台完成转写", analysisJson = "{\"profile\":{}}", report = "完整报告", kind = "audio")))
        val result = mergeSalesChanges(before, ui, worker).records.single()
        assertEquals("新标题", result.title); assertEquals(record.attachments + "more.jpg", result.attachments)
        assertEquals("后台完成转写", result.rawTranscript); assertEquals("{\"profile\":{}}", result.analysisJson)
        assertEquals("完整报告", result.report); assertEquals("audio", result.kind)
    }

    @Test fun portraitMergeIsPerFieldAndDoesNotClobberConcurrentContactEdit() {
        val before = SalesData(customers = listOf(customer))
        val ui = before.withCustomerInsight("c", portrait.copy(needs = "新需求"))
        val worker = before.copy(customers = listOf(customer.copy(phone = "123", insight = portrait.copy(budget = "新预算"))))
        val result = mergeSalesChanges(before, ui, worker).customers.single()
        assertEquals("123", result.phone); assertEquals("新需求", result.insight.needs); assertEquals("新预算", result.insight.budget)
    }

    @Test fun taskAndOpportunityUpdatesDoNotDropConcurrentMetadata() {
        val task = FollowUp(id = "f", title = "联系")
        val opportunity = Opportunity(id = "o", title = "续约")
        val before = SalesData(followUps = listOf(task), opportunities = listOf(opportunity))
        val ui = before.copy(followUps = listOf(task.copy(done = true)), opportunities = listOf(opportunity.copy(title = "续约增购")))
        val concurrent = before.copy(followUps = listOf(task.copy(priority = "高", dueTime = "14:30", reminder = true)),
            opportunities = listOf(opportunity.copy(probabilityLow = "20", probabilityHigh = "40", probabilityReason = "时间待定", probabilityEvidence = "下季度再谈")))
        val result = mergeSalesChanges(before, ui, concurrent)
        assertEquals(concurrent.followUps.single().copy(done = true), result.followUps.single())
        assertEquals(concurrent.opportunities.single().copy(title = "续约增购"), result.opportunities.single())
    }

    @Test fun staleEditCannotResurrectDeletedEntitiesAndDeletionUnlinksConcurrentChildren() {
        val before = SalesData(customers = listOf(customer), records = listOf(record))
        assertTrue(mergeSalesChanges(before, before.withCustomerInsight("c", portrait.copy(needs = "更新")), SalesData()).customers.isEmpty())
        val task = FollowUp(id = "f", title = "后台跟进", customerId = "c", recordId = "r")
        val result = mergeSalesChanges(before, before.withoutCustomer("c").withoutRecord("r"), before.copy(followUps = listOf(task)))
        assertEquals(task.copy(customerId = "", recordId = ""), result.followUps.single())
    }

    @Test fun candidateParsingNeverMutatesDataAndMissingFieldsKeepConfirmedPortrait() {
        val before = SalesData(customers = listOf(customer), records = listOf(record))
        val snapshot = before.json().toString()
        val candidate = CustomerAnalysis.read(record.analysisJson)!!
        assertEquals(snapshot, before.json().toString())
        assertEquals("发送报价", candidate.actions.single().title)
        assertEquals("09:30", candidate.actions.single().dueTime)
        assertTrue(candidate.hasProbability)
        assertEquals(listOf("预算十万元"), candidate.evidence)
        val accepted = portrait.withCandidate(CustomerInsight(needs = "追加需求"))
        assertEquals(portrait.copy(needs = "追加需求"), accepted)
        assertEquals(accepted, before.withCustomerInsight("c", accepted).customers.single().insight)
        assertNull(CustomerAnalysis.read("not-json"))
    }

    @Test fun invalidProbabilityRangesCannotBeStored() {
        listOf("70" to "30", "-1" to "20", "20" to "101", "NaN" to "50", "" to "20").forEach { (low, high) ->
            assertThrows(IllegalArgumentException::class.java) {
                SalesData().withOpportunity(Opportunity(id = "o", title = "商机", probabilityLow = low, probabilityHigh = high))
            }
        }
        assertTrue(validCustomerProbability("0", "100")); assertTrue(validCustomerProbability("", ""))
    }

    @Test fun followUpRequiresRealTimeAndReminderSchedule() {
        val task = FollowUp(id = "f", title = "跟进", dueDate = "2028-02-29", dueTime = "09:30", priority = "高", reminder = true)
        assertEquals(task, SalesData().withFollowUp(task).followUps.single())
        listOf(task.copy(dueTime = "24:00"), task.copy(dueTime = "9:30"), task.copy(dueDate = ""), task.copy(dueTime = ""), task.copy(priority = "最高")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SalesData().withFollowUp(invalid) }
        }
    }

    @Test fun exportIncludesPortraitAndNeutralizesItsSpreadsheetFormula() {
        val data = SalesData(customers = listOf(customer.copy(insight = portrait.copy(preferences = "=HYPERLINK(\"bad\")"))))
        val csv = customerCsv(data, setOf("c"))
        assertTrue(csv.contains("客户画像")); assertTrue(csv.contains("采购负责人")); assertTrue(csv.contains("降低维护成本"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\""))
        assertFalse(customerCsv(data, emptySet()).contains("采购负责人"))
    }

    @Test fun secretSyncOptInSurvivesConcurrentModelNameChange() {
        val profile = ModelProfile(id = "p", key = "private")
        val before = SalesData(profiles = listOf(profile))
        val result = mergeSalesChanges(before, before.copy(profiles = listOf(profile.copy(name = "新名称"))),
            before.copy(profiles = listOf(profile.copy(syncSecret = true))))
        assertEquals(profile.copy(name = "新名称", syncSecret = true), result.profiles.single())
    }
}
