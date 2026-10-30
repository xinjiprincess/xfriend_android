package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SalesCallRecordingsTest {
    private fun local(ts: Long?): LocalDateTime? = ts?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }

    @Test fun parsesCompactRecorderNames() {
        val ts = CallRecordings.parseCapturedAt("20261016_143205.mp3", null)
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 32, 5), local(ts))
        val withDashes = CallRecordings.parseCapturedAt("通话-2026-10-16 14-32-05.m4a", null)
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 32, 5), local(withDashes))
    }

    @Test fun parsesNamesWithoutYearUsingCurrentYear() {
        val ts = CallRecordings.parseCapturedAt("10月16日 14:32 张三通话.aac", null)
        assertEquals(10, local(ts)?.monthValue)
        assertEquals(16, local(ts)?.dayOfMonth)
        assertEquals(14, local(ts)?.hour)
        assertEquals(32, local(ts)?.minute)
        assertEquals(LocalDateTime.now().year, local(ts)?.year)
    }

    @Test fun fallsBackToMediaStoreTimeWhenNameHasNoDate() {
        val fallback = 1_700_000_000_000L
        assertEquals(fallback, CallRecordings.parseCapturedAt("张三 通话录音.m4a", fallback))
        assertNull(CallRecordings.parseCapturedAt("张三 通话录音.m4a", null))
    }

    @Test fun extractsPhoneAndNormalizesIt() {
        assertEquals("13800138000", CallRecordings.parsePhone("20261016_1432 13800138000 通话.mp3"))
        assertEquals("01066505088", CallRecordings.parsePhone("北京 010-66505088.wav"))
    }

    @Test fun stripsDateAndPhoneFromTitle() {
        assertEquals("张三", CallRecordings.titleOf("20261016_1432 张三 13800138000.mp3"))
        assertEquals("通话录音", CallRecordings.titleOf("2026-10-16 14-32-05.mp3"))
    }

    @Test fun formatsDurationAndSize() {
        assertEquals("0:05", CallRecordings.durationText(5_000))
        assertEquals("12:03", CallRecordings.durationText(723_000))
        assertEquals("1:02:03", CallRecordings.durationText(3_723_000))
        assertEquals("时长未知", CallRecordings.durationText(null))
        assertEquals("1.5 MB", CallRecordings.sizeText(1_572_864))
    }

    @Test fun communicationRoundTripsSourceFields() {
        val record = Communication(id = "r1", title = "张三 · 通话", kind = "audio",
            sourceUri = "content://media/external/audio/media/1", sourceName = "20261016_1432.mp3", sourceDeleted = true)
        val restored = Communication.read(JSONObject(record.json().toString()))
        assertEquals(record, restored)
    }

    @Test fun legacyCommunicationDefaultsSourceFields() {
        val legacy = JSONObject().put("id", "r2").put("title", "旧记录").put("created", 1L)
        val record = Communication.read(legacy)
        assertEquals("", record.sourceUri)
        assertEquals("", record.sourceName)
        assertFalse(record.sourceDeleted)
    }

    @Test fun salesDataRoundTripsCallRecordingSettings() {
        val data = SalesData(callAutoScan = false, callDeleteAfterTranscribe = false,
            callSkipped = listOf("content://media/external/audio/media/9", "local.mp3"))
        val restored = SalesData.read(JSONObject(data.json().toString()))
        assertEquals(data.callAutoScan, restored.callAutoScan)
        assertEquals(data.callDeleteAfterTranscribe, restored.callDeleteAfterTranscribe)
        assertEquals(data.callSkipped, restored.callSkipped)
    }

    @Test fun legacySalesDataDefaultsCallSettingsOn() {
        val restored = SalesData.read(JSONObject())
        assertTrue(restored.callAutoScan)
        assertTrue(restored.callDeleteAfterTranscribe)
        assertTrue(restored.callSkipped.isEmpty())
    }

    @Test fun scanKeyPrefersUriThenLocalFile() {
        val remote = CallRecording(uri = "content://x/1", name = "a.mp3", sizeBytes = 1, durationMs = null, capturedAt = null)
        assertEquals("content://x/1", CallRecordings.scanKey(remote))
    }

    @Test fun genericRecorderNamesBecomeCallTitle() {
        assertEquals("通话录音", CallRecordings.titleOf("call_20261016_1505.wav"))
        assertEquals("通话录音", CallRecordings.titleOf("录音_20261016_1432.wav"))
        assertEquals("张三", CallRecordings.titleOf("20261016_1432 张三.mp3"))
    }

    @Test fun computesWavDurationFromHeader() {
        val file = java.io.File.createTempFile("callrec", ".wav")
        try {
            val bytes = 2 * 32000
            java.io.RandomAccessFile(file, "rw").use { f ->
                f.write(wavHeader(bytes, 16000, 1))
                f.setLength(44L + bytes)
            }
            assertEquals(2000L, CallRecordings.wavDurationMs(file))
        } finally {
            file.delete()
        }
    }
}
