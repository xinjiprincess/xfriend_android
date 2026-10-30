package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class PhonePolicyTest {
    @Test fun onceRequiresFutureOffsetAndRejectsPast() {
        val now = Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-29T01:00:00Z").toEpochMilli(),
            PhonePolicy.nextTime("once", "2026-10-29T09:00:00+08:00", "", "Asia/Shanghai", now))
        assertThrows(IllegalArgumentException::class.java) { PhonePolicy.nextTime("once", "2026-10-29T08:00:00+08:00", "", "Asia/Shanghai", now) }
        assertThrows(Exception::class.java) { PhonePolicy.nextTime("once", "2026-10-30T09:00:00", "", "Asia/Shanghai", now) }
    }
    @Test fun dailyRollsToNextDayAndRejectsUnsupportedRules() {
        val now = OffsetDateTime.parse("2026-10-29T10:00:00+08:00").toInstant().toEpochMilli()
        assertEquals(OffsetDateTime.parse("2026-10-30T09:00:00+08:00").toInstant().toEpochMilli(),
            PhonePolicy.nextTime("daily", "", "09:00", "Asia/Shanghai", now))
        assertThrows(IllegalArgumentException::class.java) { PhonePolicy.nextTime("weekly", "", "09:00", "Asia/Shanghai", now) }
        assertThrows(Exception::class.java) { PhonePolicy.nextTime("daily", "", "29:00", "Asia/Shanghai", now) }
    }
    @Test fun daylightSavingGapSkipsMissingWallTimeAndOverlapDoesNotDoubleFire() {
        val beforeGap = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), PhonePolicy.nextTime("daily", "", "02:30", "America/New_York", beforeGap))
        val afterFirstOccurrence = Instant.parse("2026-11-01T05:45:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilli(), PhonePolicy.nextTime("daily", "", "01:30", "America/New_York", afterFirstOccurrence))
    }
    @Test fun fileNamesNeverAcceptPathsOrControlCharacters() {
        assertEquals("会议笔记.md", PhonePolicy.fileName("会议笔记.md"))
        listOf("../secret", "/sdcard/a", "..", "a\\b", "a\u0000b", "a:b", " trailing ").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { PhonePolicy.fileName(name) }
        }
    }
    @Test fun replayFingerprintIgnoresObjectOrderButBindsArgumentsAndTool() {
        val a = JSONObject().put("title", "开会").put("options", JSONObject().put("b", 2).put("a", 1))
        val b = JSONObject().put("options", JSONObject().put("a", 1).put("b", 2)).put("title", "开会")
        assertEquals(PhonePolicy.fingerprint("alarm", a), PhonePolicy.fingerprint("alarm", b))
        assertNotEquals(PhonePolicy.fingerprint("alarm", a), PhonePolicy.fingerprint("item", b))
        b.put("title", "接孩子")
        assertNotEquals(PhonePolicy.fingerprint("alarm", a), PhonePolicy.fingerprint("alarm", b))
    }
    @Test fun credentialsAndEditableValuesAreNotExposed() {
        assertEquals("[敏感内容已隐藏]", PhonePolicy.redact("correct-horse-battery", password = true))
        assertEquals("[敏感内容已隐藏]", PhonePolicy.redact("anything typed", editable = true))
        assertEquals("[敏感内容已隐藏]", PhonePolicy.redact("验证码 135790"))
        assertFalse(PhonePolicy.redact("手机号 13812345678 邮箱 alice@example.com").contains("13812345678"))
        assertFalse(PhonePolicy.redact("alice@example.com").contains("alice"))
        assertTrue(PhonePolicy.protectedText("发送消息"))
        assertTrue(PhonePolicy.protectedText("Confirm payment"))
        assertTrue(PhonePolicy.protectedPackage("com.android.permissioncontroller"))
        assertFalse(PhonePolicy.protectedText("打开笔记"))
    }
    @Test fun snapshotRequiresSameWindowRevisionAndUnexpiredClock() {
        assertTrue(PhonePolicy.freshSnapshot("a", "a", 1, 1, 100, 1000, 8, 8))
        assertFalse(PhonePolicy.freshSnapshot("a", "a", 1, 2, 100, 1000, 8, 8))
        assertFalse(PhonePolicy.freshSnapshot("a", "a", 1, 1, 100, 1000, 8, 9))
        assertFalse(PhonePolicy.freshSnapshot("a", "a", 1, 1, 100, 20_101, 8, 8))
        assertFalse(PhonePolicy.freshSnapshot("a", "a", 1, 1, 100, 99, 8, 8))
    }
    @Test fun accountSwitchAndToggleInvalidateOldControlLease() {
        PhoneControlSession.activateScope("")
        PhoneControlSession.activateScope("alice")
        assertNull(PhoneControlSession.lease("alice"))
        PhoneControlSession.setEnabled("alice", true)
        val lease = PhoneControlSession.lease("alice")!!
        assertTrue(PhoneControlSession.valid("alice", lease))
        PhoneControlSession.activateScope("bob")
        assertFalse(PhoneControlSession.valid("alice", lease))
        PhoneControlSession.setEnabled("alice", true)
        assertFalse(PhoneControlSession.isEnabled("bob"))
        PhoneControlSession.activateScope("alice")
        assertFalse(PhoneControlSession.valid("alice", lease))
        PhoneControlSession.setEnabled("alice", true)
        val fresh = PhoneControlSession.lease("alice")!!
        PhoneControlSession.setEnabled("alice", false)
        assertFalse(PhoneControlSession.valid("alice", fresh))
        PhoneControlSession.activateScope("")
    }
    @Test fun accountKeysDoNotRevealIdentifiers() {
        assertEquals(64, PhonePolicy.scopeKey("alice@example.com/work").length)
        assertNotEquals(PhonePolicy.scopeKey("alice"), PhonePolicy.scopeKey("bob"))
        assertThrows(IllegalArgumentException::class.java) { PhonePolicy.scopeKey("") }
    }
    @Test fun dateOnlyDueDateKeepsCalendarDayWithoutInventingClock() {
        val now = java.time.Instant.parse("2026-10-29T03:00:00Z").toEpochMilli()
        assertEquals("2026-10-30", PhonePolicy.dueDate("2026-10-30", "Asia/Shanghai", now))
        assertEquals("2026-10-29", PhonePolicy.dueDate("2026-10-29", "Asia/Shanghai", now))
        assertThrows(IllegalArgumentException::class.java) { PhonePolicy.dueDate("2026-10-28", "Asia/Shanghai", now) }
        assertThrows(Exception::class.java) { PhonePolicy.dueDate("2026-02-30", "Asia/Shanghai", now) }
        assertThrows(Exception::class.java) { PhonePolicy.dueDate("明天", "Asia/Shanghai", now) }
        assertEquals("2026-10-30", PhonePolicy.dueDateFromText("帮我记住明天问Mac价格", "Asia/Shanghai", now))
        assertEquals("2026-10-31", PhonePolicy.dueDateFromText("后天下午看发布会", "Asia/Shanghai", now))
        assertEquals("", PhonePolicy.dueDateFromText("帮我看看Mac价格", "Asia/Shanghai", now))
    }
    @Test fun reopeningCapabilitiesJustBeforeAnAlarmDoesNotSkipItsPendingDelivery() {
        val now = java.time.Instant.parse("2026-10-29T00:59:59.500Z").toEpochMilli()
        val imminent = java.time.Instant.parse("2026-10-29T01:00:00Z").toEpochMilli()
        assertEquals(imminent, PhonePolicy.reconciledDailyTime(imminent, "09:00", "Asia/Shanghai", now))
        assertEquals(java.time.Instant.parse("2026-10-30T01:00:00Z").toEpochMilli(),
            PhonePolicy.reconciledDailyTime(imminent, "09:00", "Asia/Shanghai", imminent + 500))
    }

}
