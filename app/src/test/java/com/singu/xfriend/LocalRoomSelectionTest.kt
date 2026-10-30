package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test

class LocalRoomSelectionTest {
    private val room = LocalRoom("room", "讨论", listOf("a", "b"))

    @Test fun restoringOneSpeakerKeepsOnlyThatSpeaker() {
        assertEquals("b", localRoomReplyTarget(room, LocalRoomTurn("room", listOf("b"))))
        assertEquals("", localRoomReplyTarget(room, LocalRoomTurn("room", listOf("a", "b"))))
    }

    @Test fun staleOrForeignSpeakerSelectionRequiresExplicitNewChoice() {
        assertNull(localRoomReplyTarget(room, LocalRoomTurn("other", listOf("b"))))
        assertNull(localRoomReplyTarget(room, LocalRoomTurn("room", listOf("removed"))))
        assertNull(localRoomReplyTarget(room, LocalRoomTurn("room", listOf("b", "a"))))
    }

    @Test fun resumedOlderTaskWinsOverNewerCompletedTask() {
        val old = FriendRun(FriendRunIds.newId(), "room", "older", false, "old", state = "running",
            created = 10, updated = 40, localRoomTurn = LocalRoomTurn("room", listOf("b")))
        val newer = FriendRun(FriendRunIds.newId(), "room", "newer", false, "new", state = "completed",
            created = 20, updated = 30, localRoomTurn = LocalRoomTurn("room", listOf("a", "b")))
        val other = FriendRun(FriendRunIds.newId(), "other", "foreign", false, "other", state = "running",
            created = 50, updated = 50, localRoomTurn = LocalRoomTurn("other", listOf("a")))
        assertEquals(old, localRoomVisibleRun("room", listOf(newer, other, old)))
    }

    @Test fun latestFinishedUpdateWinsWhenNoGroupTaskIsActive() {
        val older = FriendRun(FriendRunIds.newId(), "room", "older", false, "old", state = "failed",
            created = 10, updated = 40, localRoomTurn = LocalRoomTurn("room", listOf("b")))
        val newer = FriendRun(FriendRunIds.newId(), "room", "newer", false, "new", state = "completed",
            created = 20, updated = 30, localRoomTurn = LocalRoomTurn("room", listOf("a", "b")))
        assertEquals(older, localRoomVisibleRun("room", listOf(newer, older)))
        assertNull(localRoomVisibleRun("unknown", listOf(newer, older)))
    }
}
