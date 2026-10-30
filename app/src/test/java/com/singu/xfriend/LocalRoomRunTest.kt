package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalRoomRunTest {
    @Test fun legacyTasksStillDecodeWithoutGroupMetadata() {
        val run = FriendRun(FriendRunIds.newId(), "expert", "旧任务", false, "legacy")
        assertNull(FriendRunCodec.decode(FriendRunCodec.encode(listOf(run))).single().localRoomTurn)
    }

    @Test fun idempotencyIncludesFrozenMembersAndOwnsTheirSnapshot() {
        val queue = FriendRunQueue(FriendRunMemory(), Any())
        val members = mutableListOf("a", "b")
        val turn = LocalRoomTurn("room", members)
        val first = queue.enqueue("room", "讨论", false, "request", localRoomTurn = turn)
        members.remove("b")
        assertEquals(listOf("a", "b"), first.localRoomTurn!!.memberIds)
        assertEquals(first, queue.enqueue("room", "讨论", false, "request", localRoomTurn = LocalRoomTurn("room", listOf("a", "b"))))
        assertThrows(IllegalArgumentException::class.java) {
            queue.enqueue("room", "讨论", false, "request", localRoomTurn = LocalRoomTurn("room", listOf("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            queue.enqueue("other-room", "讨论", false, "other", localRoomTurn = LocalRoomTurn("room", listOf("a")))
        }
    }

    @Test fun recoveryAndExplicitRetryKeepGroupTargetsAndMode() {
        val queue = FriendRunQueue(FriendRunMemory(), Any())
        val turn = LocalRoomTurn("room", listOf("b"))
        val run = queue.enqueue("room", "继续讨论", false, "original", conversationMode = ConversationMode.PLAN, localRoomTurn = turn)
        queue.claim()
        queue.saveCheckpoint(run.id, """{"version":1,"status":"running","steps":{}}""")
        queue.recover()
        assertEquals(turn, queue.resume(run.id).localRoomTurn)
        queue.claim(); queue.finish(run.id, "部分", "failed", "network_failed", "失败")
        val retry = queue.enqueue(run.resourceId, run.input, run.team, "retry", run.id, run.conversationMode, turn)
        assertEquals(turn, retry.localRoomTurn)
        assertEquals(ConversationMode.PLAN, retry.conversationMode)
        assertEquals("部分", queue.runs().first().partial)
        assertThrows(IllegalArgumentException::class.java) {
            queue.enqueue(run.resourceId, run.input, false, "changed", run.id, run.conversationMode, LocalRoomTurn("room", listOf("a")))
        }
    }

    @Test fun persistedGroupTargetSurvivesTaskDecodeAndEncode() {
        val run = FriendRun(FriendRunIds.newId(), "room-test", "讨论方案", false, "group-request")
        val document = JSONObject(FriendRunCodec.encode(listOf(run)))
        document.getJSONArray("runs").getJSONObject(0).put("localRoomTurn",
            JSONObject().put("roomId", "room-test").put("memberIds", JSONArray(listOf("expert-a", "expert-b"))))

        val roundTrip = JSONObject(FriendRunCodec.encode(FriendRunCodec.decode(document.toString())))
            .getJSONArray("runs").getJSONObject(0)

        assertTrue("群任务必须保留冻结的目标成员，不能解码为普通专家任务", roundTrip.has("localRoomTurn"))
        assertEquals("room-test", roundTrip.getJSONObject("localRoomTurn").getString("roomId"))
        assertEquals(listOf("expert-a", "expert-b"), roundTrip.getJSONObject("localRoomTurn").getJSONArray("memberIds").strings())
    }
}
