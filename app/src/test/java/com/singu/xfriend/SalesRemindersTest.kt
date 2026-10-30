package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SalesRemindersTest {
    @Test fun onlyOpenEnabledValidTodosSchedule() {
        val task = FollowUp(title = "联系客户", dueDate = "2026-10-15", dueTime = "14:30", reminder = true)
        assertEquals(Instant.parse("2026-10-15T06:30:00Z").toEpochMilli(), followUpReminderTime(task, ZoneId.of("Asia/Shanghai")))
        assertNull(followUpReminderTime(task.copy(done = true)))
        assertNull(followUpReminderTime(task.copy(reminder = false)))
        assertNull(followUpReminderTime(task.copy(dueDate = "invalid")))
        assertNull(followUpReminderTime(task.copy(dueTime = "25:00")))
    }
}
