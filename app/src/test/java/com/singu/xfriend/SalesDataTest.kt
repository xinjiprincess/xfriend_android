package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SalesDataTest {
    private val customer = Customer(id = "customer", name = "客户")
    private val record = Communication(id = "record", title = "拜访", customerId = customer.id)
    private val opportunity = Opportunity(id = "opportunity", customerId = customer.id, title = "续约", amount = "12345678901234567890.01", expectedDate = "2028-02-29", notes = "预算待确认")
    private val followUp = FollowUp(id = "follow-up", customerId = customer.id, title = "报价回访", dueDate = "2026-10-20", recordId = record.id)
    private fun data() = SalesData(customers = listOf(customer), records = listOf(record), opportunities = listOf(opportunity), followUps = listOf(followUp))

    @Test fun readsLegacyJsonWithoutNewListsOrSettings() {
        val old = JSONObject().put("customers", JSONArray().put(customer.json())).put("records", JSONArray().put(record.json()))
        val restored = SalesData.read(old)
        assertEquals(listOf(customer), restored.customers); assertEquals(listOf(record), restored.records)
        assertTrue(restored.opportunities.isEmpty()); assertTrue(restored.followUps.isEmpty()); assertTrue(restored.expertVersions.isEmpty())
        assertEquals(DEFAULT_TRIAL_URL, restored.trialUrl); assertTrue(restored.useTrial)
    }
    @Test fun allNewFieldsRoundTripWithoutMoneyPrecisionLoss() {
        val data = data().copy(expertVersions = listOf(ExpertRevision("expert", "旧专家", "旧 prompt", "表格")))
        val restored = SalesData.read(JSONObject(data.json().toString()))
        assertEquals(data, restored); assertEquals(opportunity.amount, restored.opportunities.single().amount)
    }
    @Test fun opportunityReadsPriorNameKeyAndWritesTitle() {
        val legacy = opportunity.json().also { it.remove("title"); it.put("name", opportunity.title) }
        assertEquals(opportunity, Opportunity.read(legacy))
        assertEquals(opportunity.title, opportunity.json().getString("title")); assertFalse(opportunity.json().has("name"))
    }
    @Test fun deletingCustomerUnlinksButPreservesBusinessRecords() {
        val other = Customer(id = "other", name = "乙")
        val original = data().copy(customers = listOf(customer, other), opportunities = listOf(opportunity, opportunity.copy(id = "other-opportunity", customerId = other.id)))
        val deleted = original.withoutCustomer(customer.id)
        assertEquals(listOf(other), deleted.customers); assertEquals("", deleted.records.single().customerId)
        assertEquals("", deleted.opportunities.first().customerId); assertEquals(other.id, deleted.opportunities.last().customerId)
        assertEquals(followUp.copy(customerId = ""), deleted.followUps.single()); assertEquals(opportunity.notes, deleted.opportunities.first().notes)
        assertEquals(original, original.withoutCustomer("missing"))
    }
    @Test fun deletingRecordOnlyUnlinksMatchingFollowUp() {
        val deleted = data().withoutRecord(record.id)
        assertTrue(deleted.records.isEmpty()); assertEquals(followUp.copy(recordId = ""), deleted.followUps.single())
        assertEquals(listOf(opportunity), deleted.opportunities)
    }
    @Test fun saveUpdatesByIdAndAllowsUnlinkedItems() {
        val updated = data().withOpportunity(opportunity.copy(title = "增购")).withFollowUp(followUp.copy(done = true))
        assertEquals(1, updated.opportunities.size); assertEquals("增购", updated.opportunities.single().title)
        assertEquals(1, updated.followUps.size); assertTrue(updated.followUps.single().done)
        assertEquals(1, SalesData().withOpportunity(opportunity.copy(customerId = "")).opportunities.size)
        assertEquals(1, SalesData().withFollowUp(followUp.copy(customerId = "", recordId = "")).followUps.size)
    }
    @Test fun invalidAmountsDatesAndMissingReferencesRejected() {
        listOf("-1", "NaN", "1e4", "12.345", "12,000", " ").forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) { data().withOpportunity(opportunity.copy(amount = amount)) }
        }
        listOf("2026-02-29", "2026-13-01", "2026-1-01", "tomorrow").forEach { date ->
            assertThrows(IllegalArgumentException::class.java) { data().withOpportunity(opportunity.copy(expectedDate = date)) }
            assertThrows(IllegalArgumentException::class.java) { data().withFollowUp(followUp.copy(dueDate = date)) }
        }
        assertThrows(IllegalArgumentException::class.java) { data().withOpportunity(opportunity.copy(customerId = "missing")) }
        assertThrows(IllegalArgumentException::class.java) { data().withFollowUp(followUp.copy(recordId = "missing")) }
        assertThrows(IllegalArgumentException::class.java) { data().withFollowUp(followUp.copy(customerId = "")) }
    }
}
