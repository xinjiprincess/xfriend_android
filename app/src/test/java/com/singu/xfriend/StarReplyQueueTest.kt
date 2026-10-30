package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StarReplyQueueTest {
    private class Storage : FriendRunStorage {
        var encoded = FriendRunCodec.encode(emptyList())
        override fun read(): List<FriendRun> = FriendRunCodec.decode(encoded)
        override fun write(runs: List<FriendRun>) { encoded = FriendRunCodec.encode(runs) }
    }

    @Test fun duplicateSubmissionKeepsOneRunAndRejectsChangedReplyTarget() {
        val storage = Storage()
        val queue = FriendRunQueue(storage, Any())
        val reply = StarReply("message-1", "assistant", "第一条回复")
        val accepted = queue.enqueue("xfriend", "继续解释", false, "request-1", reply = reply)

        assertEquals(accepted, queue.enqueue("xfriend", "继续解释", false, "request-1", reply = reply))
        assertThrows(IllegalArgumentException::class.java) {
            queue.enqueue("xfriend", "继续解释", false, "request-1", reply = reply.copy(messageId = "message-2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            queue.enqueue("xfriend", "继续解释", false, "request-1")
        }
        assertEquals(1, queue.runs().size)
        assertReply(reply, queue.runs().single())
    }

    @Test fun serializedReplySurvivesProcessRecoveryAndExplicitResume() {
        val storage = Storage()
        val queue = FriendRunQueue(storage, Any())
        val reply = StarReply("message-1", "user", "原始问题\n以及第二行")
        val accepted = queue.enqueue("xfriend", "补充一下", false, "request-1", reply = reply)
        queue.claim()
        queue.checkpoint(accepted.id, "部分输出")
        queue.saveCheckpoint(accepted.id, JSONObject().put("version", 1).put("phase", "ready").put("turn", 1).toString())

        val reopened = FriendRunQueue(storage, Any())
        reopened.recover()
        val interrupted = reopened.runs().single()
        assertEquals("interrupted", interrupted.state)
        assertEquals("部分输出", interrupted.partial)
        assertReply(reply, interrupted)
        val resumed = reopened.resume(accepted.id)
        assertEquals(accepted.id, resumed.id)
        assertEquals("queued", resumed.state)
        assertReply(reply, reopened.claim()!!)
    }

    @Test fun legacyQueuedRunWithoutReplyFieldsStillLoadsAsAnOrdinaryTurn() {
        val run = FriendRun(FriendRunIds.newId(), "xfriend", "旧任务", false, "legacy-1")
        val json = JSONObject(FriendRunCodec.encode(listOf(run)))
        val row = json.getJSONArray("runs").getJSONObject(0)
        listOf("replyToId", "replyToRole", "replyPreview").forEach(row::remove)

        assertEquals(run, FriendRunCodec.decode(json.toString()).single())
    }

    private fun assertReply(expected: StarReply, actual: FriendRun) {
        assertEquals(expected.messageId, actual.replyToId)
        assertEquals(expected.role, actual.replyToRole)
        assertEquals(expected.preview, actual.replyPreview)
    }
}
