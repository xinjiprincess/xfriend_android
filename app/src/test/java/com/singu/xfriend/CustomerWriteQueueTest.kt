package com.singu.xfriend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerWriteQueueTest {
    @Test fun quickRevertIsNotMistakenForAnUnchangedSaveWhileEarlierWriteIsPending() = runTest {
        val queue = CustomerWriteQueue()
        val original = Communication(id = "r", title = "原题", created = 1L)
        val displayed = SalesData(records = listOf(original))
        val first = queue.prepare(displayed) { it.copy(records = listOf(original.copy(title = "新题"))) }!!
        val revert = queue.prepare(displayed) { it.copy(records = listOf(original)) }!!
        var disk = mergeSalesChanges(first.before, first.next, displayed)
        queue.finished()
        assertEquals("新题", disk.records.single().title)
        disk = mergeSalesChanges(revert.before, revert.next, disk)
        queue.finished()
        assertEquals("原题", disk.records.single().title)
        assertNull(queue.prepare(disk) { it })
    }

    @Test fun busyWritesWaitAndCommitInSubmissionOrder() = runTest {
        val queue = CustomerWriteQueue()
        val idle = CompletableDeferred<Unit>()
        val firstCommit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch { queue.commit({ idle.await() }) { events.add("first-start"); firstCommit.await(); events.add("first-done") } }
        val second = launch { queue.commit({ idle.await() }) { events.add("second") } }
        runCurrent()
        assertTrue(events.isEmpty()); assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        idle.complete(Unit); runCurrent()
        assertEquals(listOf("first-start"), events)
        firstCommit.complete(Unit); runCurrent()
        assertEquals(listOf("first-start", "first-done", "second"), events)
        assertTrue(first.isCompleted); assertTrue(second.isCompleted)
    }

    @Test fun failedWriteDoesNotDiscardNextConfirmedEdit() = runTest {
        val queue = CustomerWriteQueue()
        val idle = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch { try { queue.commit({ idle.await() }) { error("disk failure") } } catch (failure: IllegalStateException) { events.add(failure.message!!) } }
        launch { queue.commit({ idle.await() }) { events.add("saved") } }
        runCurrent(); idle.complete(Unit); runCurrent()
        assertEquals(listOf("disk failure", "saved"), events)
    }

    @Test fun queuedUiSnapshotPreservesAnalysisCompletedDuringBusyWork() = runTest {
        val queue = CustomerWriteQueue()
        val idle = CompletableDeferred<Unit>()
        val record = Communication(id = "r", title = "旧标题", created = 1L)
        val before = SalesData(records = listOf(record))
        val next = before.copy(records = listOf(record.copy(title = "编辑后的标题")))
        var disk = before
        launch { queue.commit({ idle.await() }) { disk = mergeSalesChanges(before, next, disk) } }
        runCurrent()
        disk = before.copy(records = listOf(record.copy(rawTranscript = "完成转写", analysisJson = "{}", report = "报告", attachments = listOf("a.jpg"))))
        idle.complete(Unit); runCurrent()
        assertEquals("编辑后的标题", disk.records.single().title)
        assertEquals("完成转写", disk.records.single().rawTranscript)
        assertEquals("{}", disk.records.single().analysisJson)
        assertEquals("报告", disk.records.single().report)
        assertEquals(listOf("a.jpg"), disk.records.single().attachments)
    }
}
