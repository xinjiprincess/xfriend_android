package com.singu.xfriend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesConcurrencyTest {
    @Test fun queuedTitleEditPreservesNewReviewFields() {
        val record = Communication(id = "record", title = "Before", text = "Original")
        val before = SalesData(records = listOf(record))
        val pending = before.copy(records = listOf(record.copy(title = "Renamed")))
        val reviewed = record.copy(
            text = "Corrected transcript",
            rawTranscript = "Raw transcript",
            report = "Completed report",
            analysisJson = "{\"evidence\":[]}",
            mode = "风险预警",
        )
        val latest = before.copy(records = listOf(reviewed))

        val result = mergeSalesChanges(before, pending, latest)

        assertEquals(listOf(reviewed.copy(title = "Renamed")), result.records)
    }

    @Test fun queuedMetadataEditDoesNotResurrectDeletedRecord() {
        val customer = Customer(id = "customer", name = "Customer")
        val record = Communication(id = "deleted", title = "Before")
        val retained = Communication(id = "retained", title = "Other record")
        val before = SalesData(customers = listOf(customer), records = listOf(record, retained))
        val pending = before.copy(records = listOf(
            record.copy(title = "Renamed", customerId = customer.id), retained,
        ))
        val latest = before.copy(records = listOf(retained))

        val result = mergeSalesChanges(before, pending, latest)

        assertEquals(listOf(retained), result.records)
        assertTrue(result.records.none { it.id == record.id })
    }
}
