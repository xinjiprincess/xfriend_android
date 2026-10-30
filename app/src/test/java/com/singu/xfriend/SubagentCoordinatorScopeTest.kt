package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubagentCoordinatorScopeTest {
    private class Ledger {
        val memory = FriendRunMemory()
        private var now = 1_000L
        private var requests = 0
        val queue = FriendRunQueue(memory, Any()) { now++ }

        fun runningRoot(resource: String = "xfriend", team: Boolean = false, room: LocalRoomTurn? = null): FriendRun {
            val root = queue.enqueue(resource, "主任务 ${requests}", team, "request-${requests++}", localRoomTurn = room)
            assertEquals(root.id, queue.claimConcurrent()!!.id)
            return queue.runs().first { it.id == root.id }
        }

        fun completedTree(resource: String = "xfriend", team: Boolean = false, room: LocalRoomTurn? = null,
            summary: String = "已完成的结果"): Pair<FriendRun, FriendRun> {
            val root = runningRoot(resource, team, room)
            val child = queue.spawnChild(root.id, "worker", "独立任务 ${requests}", "child-${requests++}")
            queue.finish(root.id, "已派发", "completed", errorMessage = "已完成")
            assertEquals(child.id, queue.claimConcurrent()!!.id)
            queue.finish(child.id, summary, "completed", errorMessage = "已完成")
            return queue.runs().first { it.id == root.id } to queue.runs().first { it.id == child.id }
        }
    }

    private suspend fun rejectedWithoutWrites(adapter: PersistentSubagentAdapter, targetId: String, ledger: Ledger) {
        val before = ledger.queue.runs()
        val writes = ledger.memory.writes
        try {
            adapter.result(targetId)
            fail("Unrelated task result must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            adapter.cancel(targetId)
            fail("Unrelated task cancellation must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, ledger.queue.runs())
        assertEquals(writes, ledger.memory.writes)
    }

    @Test fun laterMainTurnCanListAndReadAnEarlierChildInTheSameConversation() = runBlocking {
        val ledger = Ledger()
        val (_, previousChild) = ledger.completedTree(summary = "此前子任务的最终结论")
        val nextRoot = ledger.runningRoot()
        val adapter = PersistentSubagentAdapter(ledger.queue, nextRoot.id, { true })
        val writes = ledger.memory.writes

        val listed = adapter.list()
        assertEquals(1, listed.getInt("total"))
        assertEquals(previousChild.id, listed.getJSONArray("tasks").getJSONObject(0).getString("task_id"))
        assertEquals("此前子任务的最终结论", adapter.result(previousChild.id).getString("result_summary"))
        assertEquals(writes, ledger.memory.writes)
        assertFalse(listed.toString().contains("agentMessages"))
    }

    @Test fun differentExpertTeamAndLocalRoomTasksStayOutsideTheMainConversationScope() = runBlocking {
        val ledger = Ledger()
        val own = ledger.completedTree(summary = "当前会话可见").second
        val foreign = listOf(
            ledger.completedTree(resource = "other", summary = "另一专家私有结果").second,
            ledger.completedTree(team = true, summary = "同编号团队私有结果").second,
            ledger.completedTree(room = LocalRoomTurn("xfriend", listOf("speaker")), summary = "同编号群私有结果").second,
            ledger.completedTree(resource = "other_room", room = LocalRoomTurn("other_room", listOf("speaker")), summary = "其他群私有结果").second,
        )
        val current = ledger.runningRoot()
        val adapter = PersistentSubagentAdapter(ledger.queue, current.id, { true })

        val visible = adapter.list().getJSONArray("tasks").objects()
        assertEquals(listOf(own.id), visible.map { it.getString("task_id") })
        foreign.forEach { rejectedWithoutWrites(adapter, it.id, ledger) }
        assertEquals("当前会话可见", adapter.result(own.id).getString("result_summary"))
    }

    @Test fun childCanInspectItsOwnDescendantButCannotListReadOrCancelItsSibling() = runBlocking {
        val ledger = Ledger()
        val root = ledger.runningRoot()
        val child = ledger.queue.spawnChild(root.id, "worker", "第一个分支", "branch-one")
        val sibling = ledger.queue.spawnChild(root.id, "worker", "第二个分支", "branch-two")
        assertEquals(child.id, ledger.queue.claimConcurrent()!!.id)
        val nested = ledger.queue.spawnChild(child.id, "worker", "自己的后代", "nested-branch")
        val adapter = PersistentSubagentAdapter(ledger.queue, child.id, { true })

        val listed = adapter.list()
        assertEquals(1, listed.getInt("total"))
        assertEquals(nested.id, listed.getJSONArray("tasks").getJSONObject(0).getString("task_id"))
        assertEquals(nested.id, adapter.result(nested.id).getString("task_id"))
        rejectedWithoutWrites(adapter, sibling.id, ledger)
        rejectedWithoutWrites(adapter, root.id, ledger)
        assertEquals("queued", ledger.queue.runs().first { it.id == sibling.id }.state)
    }

    @Test fun listingKeepsEightBoundedSummariesWhileSingleResultHasItsLargerBound() = runBlocking {
        val ledger = Ledger()
        val summary = "结".repeat(2_000)
        val children = (1..10).map { ledger.completedTree(summary = summary).second }
        val current = ledger.runningRoot()
        val adapter = PersistentSubagentAdapter(ledger.queue, current.id, { true })

        val listed = adapter.list()
        assertEquals(10, listed.getInt("total"))
        val cards = listed.getJSONArray("tasks").objects()
        assertEquals(8, cards.size)
        assertEquals(children.takeLast(8).asReversed().map { it.id }, cards.map { it.getString("task_id") })
        cards.forEach { assertEquals(summary.take(160), it.getString("result_summary")) }
        assertEquals(summary.take(1_600), adapter.result(children.last().id).getString("result_summary"))
    }
}
