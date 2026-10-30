package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class State(override val workspaceRoot: File) : RuntimeState {
        override val experts = listOf(Expert("xfriend", "Xfriend", "Be helpful", listOf("skill"), listOf("allowed")))
        override val teams = listOf(Team("team", "Team", listOf("xfriend", "xfriend")))
        override val skills = listOf(Skill("skill", "Audit", "Cite the evidence"))
        override val knowledge = listOf(Knowledge("allowed", "Manual", "widget calibration is 42"), Knowledge("private", "Private", "widget secret is 99"))
        override var messages: Map<String, List<Message>> = emptyMap()
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "test-key"
        var saves = 0
        override fun save() { saves++ }
    }

    private fun body(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
    private fun response(message: JSONObject, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish)))

    @Test fun toolRoundTripCarriesRealFileResultAndAssignedContext() = runBlocking {
        val state = State(temporary.newFolder())
        File(state.workspaceRoot, "xfriend").mkdirs()
        File(state.workspaceRoot, "xfriend/evidence.txt").writeText("measured widget=42")
        var requests = 0
        val statuses = mutableListOf<String>()
        val runtime = AgentRuntime(state) { request ->
            assertEquals("https://example.test/v1/chat/completions", request.url.toString())
            assertEquals("Bearer test-key", request.header("Authorization"))
            val payload = body(request)
            assertEquals("test-model", payload.getString("model"))
            val messages = payload.getJSONArray("messages")
            val prompt = messages.getJSONObject(0).getString("content")
            assertTrue(prompt.contains("Cite the evidence"))
            assertTrue(prompt.contains("Manual"))
            assertFalse(prompt.contains("widget secret"))
            if (requests++ == 0) {
                assertEquals(4, payload.getJSONArray("tools").length())
                response(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call-1").put("type", "function").put("function", JSONObject().put("name", "read_file")
                        .put("arguments", JSONObject().put("path", "evidence.txt").toString())))), "tool_calls")
            } else {
                val result = messages.getJSONObject(messages.length() - 1)
                assertEquals("tool", result.getString("role"))
                assertEquals("call-1", result.getString("tool_call_id"))
                assertEquals("measured widget=42", result.getString("content"))
                response(JSONObject().put("role", "assistant").put("content", "Verified widget=42"))
            }
        }
        assertEquals("Verified widget=42", runtime.run("xfriend", "widget", statuses::add))
        assertEquals(2, requests)
        assertEquals(listOf(Message("user", "widget"), Message("assistant", "Verified widget=42")), state.messages["xfriend"]?.map { it.copy(id = "") })
        assertEquals(2, state.saves)
        assertTrue(statuses.contains("工具：read_file"))
    }

    @Test fun pathGuardsTraversalAbsolutePathsAndSiblingPrefixes() {
        val root = temporary.newFolder("workspace")
        assertEquals(File(root, "nested/file.txt").canonicalFile, workspacePath(root, "nested/file.txt"))
        listOf("../workspace-other/secret", "nested/../../secret", File(root, "absolute").absolutePath, "..\\secret", "C:secret").forEach { path ->
            try { workspacePath(root, path); fail("Accepted $path") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun canonicalGuardRejectsSymlinkEscapeWhenSupported() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("outside")
        val link = File(root, "link").toPath()
        try { java.nio.file.Files.createSymbolicLink(link, outside.toPath()) }
        catch (_: UnsupportedOperationException) { return }
        catch (_: java.nio.file.FileSystemException) { return }
        try { workspacePath(root, "link/secret"); fail("Accepted symlink escape") } catch (_: IllegalArgumentException) { }
    }

    @Test fun lexicalRetrievalFiltersNonmatchesRanksAndBoundsExcerpts() {
        val result = lexicalKnowledge(listOf(
            Knowledge("a", "Relevant", "widget calibration " + "x".repeat(10000)),
            Knowledge("b", "Unrelated", "oranges and apples"),
            Knowledge("c", "Less relevant", "widget"),
        ), "widget calibration")
        assertTrue(result.startsWith("Source: Relevant [chunk 1]"))
        assertTrue(result.contains("Source: Less relevant"))
        assertFalse(result.contains("Unrelated"))
        assertTrue(result.length < 5200)
        assertEquals("未找到词法匹配的知识片段。", lexicalKnowledge(listOf(Knowledge("a", "A", "oranges")), "widget"))
    }

    @Test fun chineseQueriesMatchBigramsInsideLongParagraphs() {
        val result = lexicalKnowledge(listOf(
            Knowledge("relevant", "退款规则", "如需申请退款，请在订单页面提交申请，审核通过后款项原路退回。"),
            Knowledge("unrelated", "配送指南", "包裹将在三个工作日内送达，物流更新可在详情页查看。"),
        ), "如何申请退款？")
        assertTrue(result.contains("Source: 退款规则 [chunk 1]"))
        assertTrue(result.contains("原路退回"))
        assertFalse(result.contains("配送指南"))
        assertEquals("未找到词法匹配的知识片段。", lexicalKnowledge(listOf(Knowledge("x", "X", "物流配送")), "申请退款"))
    }
    @Test fun failureDoesNotPersistFabricatedAssistant() = runBlocking {
        val state = State(temporary.newFolder())
        val runtime = AgentRuntime(state) { throw java.io.IOException("offline") }
        try { runtime.run("xfriend", "hello") {}; fail("Expected failure") } catch (_: java.io.IOException) { }
        assertEquals(listOf(Message("user", "hello")), state.messages["xfriend"]?.map { it.copy(id = "") })
    }

    @Test fun truncatedAndEmptyResponsesNeverPersistSuccess() = runBlocking {
        listOf("length" to "", "length" to "partial answer", "stop" to "   ", "stop" to JSONObject.NULL).forEach { (finish, content) ->
            val state = State(temporary.newFolder())
            val runtime = AgentRuntime(state) {
                response(JSONObject().put("role", "assistant").put("content", content), finish)
            }
            val statuses = mutableListOf<String>()
            try { runtime.run("xfriend", "hello", statuses::add); fail("Expected rejection") }
            catch (e: FriendModelFailure) { assertEquals("TRUNCATED", e.code); assertEquals("length", finish) }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains(if (finish == "length") "截断" else "未返回")) }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("未返回")) }
            assertFalse(statuses.contains("已完成"))
            assertEquals(listOf(Message("user", "hello")), state.messages["xfriend"]?.map { it.copy(id = "") })
        }
    }

    @Test fun rejectedWriteReturnsToolFailureAndLeavesOriginalFilesIntact() = runBlocking {
        val state = State(temporary.newFolder())
        val root = File(state.workspaceRoot, "xfriend").also { it.mkdirs() }
        val existing = File(root, "existing.txt").also { it.writeText("original") }
        val outside = File(state.workspaceRoot, "outside.txt").also { it.writeText("private") }
        var requests = 0
        val runtime = AgentRuntime(state) { request ->
            if (requests++ == 0) {
                val calls = JSONArray()
                listOf("../outside.txt" to "overwrite", "existing.txt" to "x".repeat(65537)).forEachIndexed { index, (path, content) ->
                    calls.put(JSONObject().put("id", "write-$index").put("type", "function").put("function",
                        JSONObject().put("name", "write_file").put("arguments",
                            JSONObject().put("path", path).put("content", content).toString())))
                }
                response(JSONObject().put("role", "assistant").put("tool_calls", calls), "tool_calls")
            } else {
                val messages = body(request).getJSONArray("messages")
                for (index in messages.length() - 2 until messages.length()) {
                    val tool = messages.getJSONObject(index)
                    assertEquals("tool", tool.getString("role"))
                    assertTrue(tool.getString("content").startsWith("工具执行失败："))
                    assertFalse(tool.getString("content").contains("已写入："))
                }
                assertEquals("original", existing.readText())
                assertEquals("private", outside.readText())
                response(JSONObject().put("role", "assistant").put("content", "写入被拒绝，文件未改变。"))
            }
        }
        runtime.run("xfriend", "write files") {}
        assertEquals(2, requests)
    }

    @Test fun atomicWorkspaceWriteCommitsAndRollsBackFailedCommit() {
        val root = temporary.newFolder()
        val target = File(root, "note.txt").also { it.writeText("old") }
        writeWorkspaceAtomic(target, "committed")
        assertEquals("committed", target.readText())
        try {
            writeWorkspaceAtomic(target, "lost") { pending, destination ->
                assertEquals("lost", pending.readText())
                assertEquals(target, destination)
                throw java.io.IOException("injected commit failure")
            }
            fail("Expected failed commit")
        } catch (_: java.io.IOException) { }
        assertEquals("committed", target.readText())
        assertEquals(listOf("note.txt"), root.list()!!.sorted())
    }
    @Test fun cancellationPropagatesToExecutorWithoutAssistantReply() = runBlocking {
        val state = State(temporary.newFolder())
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val runtime = AgentRuntime(state) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                continuation.invokeOnCancellation { cancelled = true }
                started.complete(Unit)
            }
        }
        val job = launch { runtime.run("xfriend", "hello") {} }
        started.await()
        job.cancel()
        job.join()
        assertTrue(cancelled)
        assertEquals(listOf(Message("user", "hello")), state.messages["xfriend"]?.map { it.copy(id = "") })
    }
    @Test fun teamPassesPriorOutputAndKeepsPartialTraceOnFailure() = runBlocking {
        val state = State(temporary.newFolder())
        var requests = 0
        val runtime = AgentRuntime(state) { request ->
            if (requests++ == 0) response(JSONObject().put("role", "assistant").put("content", "first pass evidence"))
            else {
                assertTrue(body(request).getJSONArray("messages").toString().contains("first pass evidence"))
                throw java.io.IOException("second member failed")
            }
        }
        try { runtime.runTeam("team", "investigate") {}; fail("Expected failure") } catch (_: java.io.IOException) { }
        assertEquals(2, state.messages["team:team"]!!.size)
        assertTrue(state.messages["team:team"]!!.last().content.contains("first pass evidence"))
    }
}

