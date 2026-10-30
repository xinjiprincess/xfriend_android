package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class SalesUpgradeTest {
    @Test fun stereo48kBecomesMono16kAcrossOddBuffers() {
        val source = java.nio.ByteBuffer.allocate(48000 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { source.putShort(1000); source.putShort(3000) }
        val converter = SpeechPcm(48000, 2)
        val bytes = source.array().asList().chunked(997).flatMap { converter.convert(it.toByteArray()).toList() }.toByteArray()
        assertEquals(32000, bytes.size)
        val values = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (values.hasRemaining()) assertEquals(2000, values.short.toInt())
    }
    @Test fun audioBeyond20MbIsSegmentedWithoutFullFileUpload() {
        val file = File.createTempFile("large-audio", ".wav")
        try {
            val size = 25 * 1024 * 1024
            RandomAccessFile(file, "rw").use { f -> f.write(wavHeader(size)); f.setLength(size + 44L) }
            val info = wavInfo(file)
            assertTrue(info.segments > 1)
            var total = 0
            for (i in 0 until info.segments) {
                val segment = audioSegment(file, i)
                assertTrue(segment.size <= 90 * 32000 + 44)
                total += segment.size - 44
            }
            assertEquals(size, total)
        } finally { file.delete() }
    }
    @Test fun highRateLegacyAudioAlsoHasSmallSegments() {
        val file = File.createTempFile("legacy", ".wav")
        try {
            RandomAccessFile(file, "rw").use { f -> f.write(wavHeader(48000 * 4, 48000, 2)); f.setLength(48000 * 4 + 44L) }
            assertEquals(32044, audioSegment(file, 0).size)
        } finally { file.delete() }
    }
    @Test fun malformedWavFailsClearly() {
        val file = File.createTempFile("broken", ".wav")
        try { file.writeBytes(wavHeader(300)); assertTrue(runCatching { wavInfo(file) }.isFailure) } finally { file.delete() }
    }
    @Test fun longTextChunksCoverEverything() {
        val text = "客户需求和原文证据".repeat(9000)
        val chunks = splitSalesText(text)
        assertTrue(chunks.size > 1); assertTrue(chunks.all { it.length <= 8000 }); assertEquals(text, chunks.joinToString(""))
    }
    @Test fun csvQuotesUnicodeAndNeutralizesSpreadsheetFormulas() {
        val customer = Customer("c", "客户,\"陈\"", phone = "13800000000", company = "  =HYPERLINK(1)", notes = "第一行\n第二行", tags = "重点,续约")
        val csv = customerCsv(SalesData(customers = listOf(customer)), setOf("c"))
        assertTrue(csv.startsWith("\uFEFF")); assertTrue(csv.contains("\"客户,\"\"陈\"\"\"")); assertTrue(csv.contains("'  =HYPERLINK(1)")); assertTrue(csv.contains("第一行\n第二行"))
        assertFalse(customerCsv(SalesData(customers = listOf(customer)), emptySet()).contains("13800000000"))
    }
    @Test fun oldCustomerJsonRemainsReadableAndNewFieldsRoundTrip() {
        val old = Customer.read(JSONObject("""{"id":"c","name":"陈经理","notes":"旧备注"}"""))
        assertEquals("", old.email); assertEquals("普通", old.priority)
        val next = old.copy(tags = "重点", email = "sales@example.com", nextContact = "2026-10-30")
        assertEquals(next, Customer.read(next.json()))
    }
    @Test fun uiCustomerEditsDoNotOverwriteWorkerTranscript() {
        val c = Customer("c", "客户")
        val record = Communication("r", "录音", "c")
        val before = SalesData(customers = listOf(c), records = listOf(record))
        val next = before.copy(customers = listOf(c.copy(tags = "重点")), records = listOf(record.copy(title = "新标题")))
        val latest = before.copy(records = listOf(record.copy(text = "后台转写", report = "后台报告"), Communication("new", "后台导入")))
        val result = mergeSalesChanges(before, next, latest)
        assertEquals("重点", result.customers.single().tags)
        assertEquals("后台转写", result.records.first { it.id == "r" }.text)
        assertEquals("新标题", result.records.first { it.id == "r" }.title)
        assertTrue(result.records.any { it.id == "new" })
    }
    @Test fun deletedRecordIsNotResurrectedByStaleUi() {
        val original = Communication("r", "原文")
        val before = SalesData(records = listOf(original))
        val result = mergeSalesChanges(before, before.copy(records = listOf(original.copy(title = "修改"))), SalesData())
        assertTrue(result.records.isEmpty())
    }
    @Test fun networkFailuresHaveSpecificActionableMessages() {
        assertTrue(salesNetworkError(UnknownHostException()).contains("域名"))
        assertTrue(salesNetworkError(SSLHandshakeException("bad cert")).contains("证书"))
        assertTrue(salesNetworkError(SocketTimeoutException()).contains("超时"))
    }
    @Test fun taskCheckpointRoundTrips() {
        val task = SalesTask(operation = "analyze", title = "长录音", payload = JSONObject().put("parts", org.json.JSONArray(listOf("第一段", "第二段"))).toString(), status = "failed")
        assertEquals(task, SalesTask.read(task.json())); assertFalse(task.active)
        assertTrue(task.copy(status = "queued").active)
    }
    @Test fun defaultExpertsHaveSixDistinctPlaybooks() {
        assertEquals(6, salesSkills.size)
        assertTrue(salesExperts.all { e -> e.skills.isNotEmpty() && e.skills.all { id -> salesSkills.any { it.id == id } } })
    }
}
