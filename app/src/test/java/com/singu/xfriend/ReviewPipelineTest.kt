package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewPipelineTest {
    @Test fun abnormalAsrEndNeverCompletesAndCanRetry() = runBlocking {
        for (finish in listOf("content_filter", "null", "", "unknown", "stop")) {
            val state = JSONObject(); val range = ReviewAudioRange(0, 64000L, 16000)
            val failure = runCatching {
                ReviewTranscriber(state, {}) { _, _ -> ReviewCompletion(if (finish == "stop") "" else "原始partial", finish) }.transcribe(range)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(state.getJSONObject("leaves").has(range.key))
            assertTrue(state.getJSONObject("incompleteAttempts").has(range.key))
            val result = ReviewTranscriber(state, {}) { _, _ -> ReviewCompletion("完整原文", "stop") }.transcribe(range)
            assertEquals("完整原文", result.single().text)
        }
    }
    @Test fun trialWireProfileAndPrioritiesBecomeLocalCrmValues() {
        val analysis = JSONObject().put("profile", JSONObject().put("preferences", JSONArray(listOf("短信", "下午")))
            .put("needs", JSONArray(listOf("试用"))))
            .put("actions", JSONArray(listOf("high", "medium", "low").map {
                JSONObject().put("title", "跟进").put("priority", it).put("evidenceIds", JSONArray(listOf("e1")))
            })).put("evidence", JSONArray().put(JSONObject().put("id", "e1").put("quote", "试用")))
        val result = JSONObject().put("text", "需要试用").put("rawTranscript", "需要试用")
            .put("reportText", "安排试用[e1]").put("analysisJson", analysis)
        val local = JSONObject(trialReviewArtifact(result, Communication("id", "记录")).analysisJson)
        assertEquals("短信；下午", local.getJSONObject("profile").getString("preferences"))
        assertEquals("试用", local.getJSONObject("profile").getString("needs"))
        assertEquals(listOf("高", "普通", "低"), local.getJSONArray("actions").objects().map { it.getString("priority") })
        assertTrue(analysis.getJSONObject("profile").get("preferences") is JSONArray)
    }
    @Test fun plainCompletionRequiresExplicitSuccessfulStop() {
        for (finish in listOf("max_tokens", "content_filter", "null", "", "unknown")) {
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
                .put("message", JSONObject().put("content", "partial"))))
            assertThrows(IllegalArgumentException::class.java) { SalesApi().content(response) }
        }
    }

    @Test fun overlapCoversEveryFrameIncludingSubsecondTail() {
        val info = WavInfo(16000, 1, (181 * 16000 + 17) * 2)
        val ranges = reviewAudioRanges(info)
        assertEquals(0L, ranges.first().startFrame)
        assertEquals(info.bytes / 2L, ranges.last().endFrame)
        assertTrue(ranges.all { it.seconds <= 90 })
        ranges.zipWithNext().forEach { (left, right) -> assertEquals(32000L, left.endFrame - right.startFrame) }
    }
    @Test fun splitIsSmallerOverlapsAndPreservesOuterBoundaries() {
        val range = ReviewAudioRange(172L * 16000, 262L * 16000, 16000)
        val parts = splitReviewAudio(range)
        assertEquals(range.startFrame, parts.first().startFrame)
        assertEquals(range.endFrame, parts.last().endFrame)
        assertTrue(parts.all { it.seconds < range.seconds })
        assertTrue(parts.first().endFrame > parts.last().startFrame)
    }
    @Test fun fourHoursAllowedButNotOneFrameLonger() {
        assertTrue(reviewAudioRanges(WavInfo(16000, 1, MAX_AUDIO_SECONDS * 32000)).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { reviewAudioRanges(WavInfo(16000, 1, MAX_AUDIO_SECONDS * 32000 + 2)) }
    }
    @Test fun rangedAudioSeeksSourceFramesAndNormalizesLegacyStereo() {
        val file = java.io.File.createTempFile("review-range", ".wav")
        try {
            val pcm = java.nio.ByteBuffer.allocate(48000 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(48000) { index -> val value = if (index < 24000) 1000 else 3000; pcm.putShort(value.toShort()); pcm.putShort(value.toShort()) }
            file.writeBytes(wavHeader(pcm.capacity(), 48000, 2) + pcm.array())
            val selected = reviewAudio(file, ReviewAudioRange(24000, 48000, 48000))
            assertEquals(16000 + 44, selected.size)
            val buffer = java.nio.ByteBuffer.wrap(selected, 44, selected.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) assertEquals(3000, buffer.short.toInt())
        } finally { file.delete() }
    }
    @Test fun truncatedAsrSplitsAndResumesWithoutRepeatingSuccessfulLeaves() = runBlocking {
        val checkpoint = JSONObject(); var calls = 0; var saves = 0
        val range = ReviewAudioRange(0, 20 * 16000L, 16000)
        val transcriber = ReviewTranscriber(checkpoint, { saves++ }) { r, _ ->
            calls++; if (r.seconds > 12) ReviewCompletion("被截断的原始结果", "length") else ReviewCompletion("客户需要预算审批", "stop")
        }
        val sources = transcriber.transcribe(range)
        assertEquals(2, sources.size); assertEquals(3, calls); assertTrue(saves >= 3)
        assertTrue(checkpoint.toString().contains("被截断的原始结果"))
        assertEquals(sources, ReviewTranscriber(checkpoint, {}) { _, _ -> error("must not call") }.transcribe(range))
    }
    @Test fun minimumTruncatedLeafFailsRetainsPartialAndRetriesFresh() = runBlocking {
        val limits = mutableListOf<Int>(); val state = JSONObject()
        val range = ReviewAudioRange(0, 4 * 16000L, 16000)
        val failure = runCatching {
            ReviewTranscriber(state, {}) { _, limit -> limits.add(limit); ReviewCompletion("客户下周", "length") }.transcribe(range)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(4096, 16384), limits)
        assertFalse(state.getJSONObject("leaves").has(range.key))
        assertFalse(state.getJSONObject("attempts").has(range.key + "-expanded"))
        assertEquals("客户下周", state.getJSONObject("incompleteAttempts").getJSONArray(range.key).getJSONObject(0).getJSONObject("expanded").getString("text"))
        var retryCalls = 0
        val result = ReviewTranscriber(state, {}) { _, _ -> retryCalls++; ReviewCompletion("客户下周审批预算", "stop") }.transcribe(range)
        assertEquals(1, retryCalls); assertEquals("客户下周审批预算", result.single().text)
        assertTrue(state.getJSONObject("incompleteAttempts").has(range.key))
    }
    @Test fun cachedTruncatedExpandedResponseAfterCrashDoesNotPoisonRetry() = runBlocking {
        val range = ReviewAudioRange(0, 4 * 16000L, 16000)
        val initial = JSONObject().put("text", "初次片段").put("finish", "length")
        val expanded = JSONObject().put("text", "扩展仍截断").put("finish", "max_tokens")
        val state = JSONObject().put("attempts", JSONObject().put(range.key, initial).put(range.key + "-expanded", expanded))
        var calls = 0
        val result = ReviewTranscriber(state, {}) { _, _ -> calls++; ReviewCompletion("新模型完整原文", "stop") }.transcribe(range)
        assertEquals(1, calls); assertEquals("新模型完整原文", result.single().text)
        assertEquals("扩展仍截断", state.getJSONObject("incompleteAttempts").getJSONArray(range.key).getJSONObject(0).getJSONObject("expanded").getString("text"))
    }
    @Test fun failedMinimumLeafDoesNotInvalidateItsSuccessfulSibling() = runBlocking {
        val state = JSONObject(); val range = ReviewAudioRange(0, 8 * 16000L, 16000)
        val failure = runCatching {
            ReviewTranscriber(state, {}) { r, _ -> when {
                r.seconds > 5 -> ReviewCompletion("父片段", "length")
                r.startFrame == 0L -> ReviewCompletion("已完成的前半段", "stop")
                else -> ReviewCompletion("后半段截断", "length")
            } }.transcribe(range)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        var calls = 0
        val result = ReviewTranscriber(state, {}) { _, _ -> calls++; ReviewCompletion("后半段完整", "stop") }.transcribe(range)
        assertEquals(1, calls); assertEquals(listOf("已完成的前半段", "后半段完整"), result.map { it.text })
    }
    @Test fun cancellationMergeKeepsNewLeaseAndFlagsAlongsideWorkerCheckpoint() {
        val worker = JSONObject().put("lease", JSONObject().put("token", "old"))
            .put("review", JSONObject().put("partial", "新到的报告"))
            .put("remoteCancelConfirmed", false).put("remoteCancelState", "stale")
        val latest = JSONObject().put("lease", JSONObject().put("token", "new"))
            .put("review", JSONObject().put("partial", "旧报告"))
            .put("cancelRequested", true).put("remoteCancelConfirmed", true).put("remoteCancelState", "cancelled")
        val merged = mergeCancelledReviewPayload(worker, latest)
        assertEquals("new", merged.getJSONObject("lease").getString("token"))
        assertEquals("新到的报告", merged.getJSONObject("review").getString("partial"))
        assertTrue(merged.getBoolean("cancelRequested")); assertTrue(merged.getBoolean("remoteCancelConfirmed"))
        assertEquals("cancelled", merged.getString("remoteCancelState"))
        assertEquals("old", worker.getJSONObject("lease").getString("token"))
        assertFalse(mergeCancelledReviewPayload(worker, JSONObject()).has("remoteCancelState"))
    }
    @Test fun cancellationKeepsFirstLeafCheckpoint() = runBlocking {
        val state = JSONObject(); var calls = 0
        val range = ReviewAudioRange(0, 20 * 16000L, 16000)
        try {
            ReviewTranscriber(state, {}) { r, _ -> calls++; when {
                r.seconds > 12 -> ReviewCompletion("头部", "length")
                r.startFrame == 0L -> ReviewCompletion("已经保存", "stop")
                else -> throw CancellationException()
            } }.transcribe(range)
            fail("should cancel")
        } catch (_: CancellationException) { }
        var resumedCalls = 0
        val result = ReviewTranscriber(state, {}) { _, _ -> resumedCalls++; ReviewCompletion("接续", "stop") }.transcribe(range)
        assertEquals(1, resumedCalls); assertEquals("已经保存", result.first().text)
    }
    @Test fun longScreenshotRegionsKeepNativeWidthAndCoverBottom() {
        val regions = reviewImageRegions(1080, 30000)
        assertTrue(regions.size > 10)
        assertEquals(30000, regions.last().bottom)
        assertTrue(regions.all { it.left == 0 && it.right == 1080 && it.bottom - it.top <= 2000 })
        regions.zipWithNext().forEach { (a, b) -> assertEquals(128, a.bottom - b.top) }
        assertEquals("0,0,1080,2000", reviewAssetRegion("uuid-tile-0-0-1080-2000.jpg"))
        assertEquals("0,1872,1080,2000", reviewAssetRegion("uuid-tile-0-1872-1080-3872.jpg"))
    }
    @Test fun exactQuotesOnlyAndLocationsComeFromSource() {
        val source = ReviewSource("s1-1", "客户说预算八万元，下周审批。", 88.0, "asset.jpg", "0,0,100,200")
        val evidence = reviewEvidence(source, JSONArray(listOf("预算八万元", "预算十万元", "预算八万元")))
        assertEquals(1, evidence.size); assertEquals(88.0, evidence.single().getDouble("startSeconds"), 0.0)
        assertEquals("asset.jpg", evidence.single().getString("asset"))
        validateReviewCitations("预算八万元 [s1-1-e1]", evidence)
        assertThrows(IllegalArgumentException::class.java) { validateReviewCitations("预算十万元 [s99-e2]", evidence) }
        assertThrows(IllegalArgumentException::class.java) { validateReviewCitations("无引用", evidence) }
    }
    @Test fun invalidActionsAndUnsupportedProbabilitiesCannotBeApplied() {
        val evidence = reviewEvidence(ReviewSource("s1-1", "下周联系"), JSONArray(listOf("下周联系")))
        val candidate = JSONObject("""{"actions":[{"title":"回访","dueDate":"2026-99-99","evidenceIds":["s1-1-e1"]},{"title":"乱写","evidenceIds":["fake"]}],"probability":{"low":80,"high":90,"reason":"感觉很好"}}""")
        val safe = validatedReviewAnalysis(candidate, evidence)
        assertEquals(1, safe.getJSONArray("actions").length())
        assertEquals("", safe.getJSONArray("actions").getJSONObject(0).getString("dueDate"))
        assertFalse(safe.getJSONObject("probability").has("low"))
    }
    @Test fun partialPersistenceIsBoundedAndForcedFinalIsNotLost() {
        var now = 0L; val saved = mutableListOf<String>()
        val writer = ReviewPartialWriter(1000, { now }) { saved.add(it) }
        writer.accept("a"); repeat(500) { writer.accept("b$it") }; assertEquals(listOf("a"), saved)
        now = 1000; writer.accept("c"); writer.accept("complete", true); writer.accept("complete", true)
        assertEquals(listOf("a", "c", "complete"), saved)
    }
    @Test fun taskMigrationAndCompletedRecordIdentity() {
        val old = SalesTask(operation = "analyze", title = "录音", payload = "{}").json().apply { remove("progress"); remove("recordId") }
        assertEquals(0, SalesTask.read(old).progress)
        val task = SalesTask.read(old).copy(progress = 100, status = "done", recordId = "record", payload = "{}")
        assertEquals(task, SalesTask.read(task.json())); assertEquals("正在复盘 · 62%", reviewProgress(62))
    }
    @Test fun boundaryDedupDoesNotDropNonoverlappingText() {
        val left = "开始讨论。客户预算是八万元整，"
        val right = "客户预算是八万元整，下周审批。"
        assertEquals("开始讨论。客户预算是八万元整，下周审批。", joinReviewBoundary(left, right))
        assertEquals("完全不同\n新的内容", joinReviewBoundary("完全不同", "新的内容"))
    }
    @Test fun trialResultChecksRawQuotesAndAcceptsGroupedCitations() {
        val evidence = JSONArray().put(JSONObject().put("id", "e1").put("quote", "下周联系"))
            .put(JSONObject().put("id", "e2").put("quote", "预算八万"))
        val result = JSONObject().put("rawTranscript", "下周联系，预算八万").put("correctedText", "下周联系，预算八万")
            .put("reportText", "下周回访并确认预算 [e1, e2]").put("evidence", evidence)
            .put("analysisJson", JSONObject().put("evidence", evidence).put("actions", JSONArray()))
        val item = Communication("id", "录音")
        assertTrue(trialReviewArtifact(result, item).report.contains("[e1, e2]"))
        val preserved = trialReviewArtifact(result, item.copy(rawTranscript = "最初ASR原文")).rawTranscript
        assertTrue(preserved.contains("最初ASR原文") && preserved.contains("下周联系，预算八万"))
        result.put("rawTranscript", "完全不同")
        assertThrows(IllegalArgumentException::class.java) { trialReviewArtifact(result, item) }
    }
    @Test fun imageAnalysisSelectsTilesButKeepsPrimaryAssetOnRecord() {
        val item = Communication("id", "截图", kind = "image", file = "id.jpg",
            attachments = listOf("id.jpg", "id-tile-0-0-1080-2000.jpg", "id-image-1.jpg", "id-image-1-tile-0-0-1080-2000.jpg"))
        assertEquals(2, reviewImageAssets(item).size)
        assertTrue(item.attachments.all { it.matches(Regex("[a-zA-Z0-9-]+\\.(wav|mp3|jpg)")) })
        assertEquals("id.jpg", item.file)
        assertEquals("id.jpg", Communication.read(item.json()).file)
        assertTrue("plain.jpg" in reviewImageAssets(item.copy(attachments = item.attachments + "plain.jpg")))
    }
    @Test fun trialTextPartsRespectUtf8LimitAndDoNotSplitSurrogates() {
        val original = "中".repeat(199999) + "😀" + "证据".repeat(500000)
        val parts = reviewTextParts(original)
        assertEquals(original, parts.joinToString(""))
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024 })
        assertTrue(parts.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
    @Test fun streamingKeepsActualDeltasAndRejectsIncompleteOrTruncatedStreams() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val head = "data: {\"choices\":[{\"delta\":{\"content\":\"客户预算\"},\"finish_reason\":null}]}\n\n"
            val tail = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(head + tail))
            val parts = mutableListOf<String>()
            assertEquals("客户预算", SalesApi().stream(Request.Builder().url(server.url("/")).build()) { parts.add(it) })
            assertEquals(listOf("客户预算"), parts)
            for (body in listOf(head, head + tail.replace("stop", "length"))) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
                assertTrue(runCatching { SalesApi().stream(Request.Builder().url(server.url("/")).build()) {} }.isFailure)
            }
        }
    }
}
