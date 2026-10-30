package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SubagentRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class State(override val workspaceRoot: File, override val conversationMode: String = ConversationMode.CRAFT) : RuntimeState {
        override val experts = listOf(Expert("xfriend", "星助理", "帮助完成当前任务"), Expert("other", "其他", "帮助完成当前任务"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = mapOf("xfriend" to listOf(Message("user", "主会话私有内容", "root-message")))
        override var runtimeHistorySteps = emptySet<String>()
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "test-only-key"
        override fun save() {}
    }

    private class Storage : FriendRunStorage {
        private var encoded = FriendRunCodec.encode(emptyList())
        override fun read(): List<FriendRun> = FriendRunCodec.decode(encoded)
        override fun write(runs: List<FriendRun>) { encoded = FriendRunCodec.encode(runs) }
    }

    private fun parent(queue: FriendRunQueue, mode: String = ConversationMode.CRAFT): FriendRun {
        queue.enqueue("xfriend", "父任务", false, "parent", conversationMode = mode)
        return queue.claimConcurrent()!!
    }

    private fun payload(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun answer(text: String): JSONObject = response(JSONObject().put("role", "assistant").put("content", text))
    private fun response(message: JSONObject, finish: String = "stop"): JSONObject = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", finish).put("message", message)))
    private fun tool(name: String, arguments: JSONObject, id: String = "provider-call-1"): JSONObject = response(
        JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", id).put("type", "function").put("function",
                JSONObject().put("name", name).put("arguments", arguments.toString())))), "tool_calls")

    @Test fun siblingContextsWorkspacesAndHistoriesStayDetachedAndReloadFromTheSameContext() = runBlocking {
        val storage = Storage()
        val queue = FriendRunQueue(storage, Any())
        val root = parent(queue)
        val first = queue.spawnChild(root.id, "xfriend", "第一项独立任务", "child-1")
        val second = queue.spawnChild(root.id, "xfriend", "第二项独立任务", "child-2")
        assertNotEquals(first.contextId, second.contextId)
        queue.claimConcurrent(); queue.claimConcurrent()
        val base = State(temporary.newFolder())
        val originalRootHistory = base.messages
        val firstState = IsolatedAgentState(base, first) { messages, steps -> queue.saveAgentContext(first.id, messages, steps) }
        val secondState = IsolatedAgentState(base, second) { messages, steps -> queue.saveAgentContext(second.id, messages, steps) }
        assertNotEquals(firstState.workspaceRoot.canonicalFile, secondState.workspaceRoot.canonicalFile)
        assertNotEquals(base.workspaceRoot.canonicalFile, firstState.workspaceRoot.canonicalFile)
        AgentRuntime(firstState) { request ->
            val content = payload(request).getJSONArray("messages").toString()
            assertFalse(content.contains("主会话私有内容"))
            answer("第一项私有结果")
        }.run("xfriend", first.input) {}
        File(firstState.workspaceRoot, "xfriend/private.txt").writeText("只属于第一项任务")
        AgentRuntime(secondState) { request ->
            val content = payload(request).getJSONArray("messages").toString()
            assertFalse(content.contains("主会话私有内容"))
            assertFalse(content.contains("第一项私有结果"))
            answer("第二项私有结果")
        }.run("xfriend", second.input) {}

        assertEquals(originalRootHistory, base.messages)
        assertFalse(File(secondState.workspaceRoot, "xfriend/private.txt").exists())
        assertFalse(File(base.workspaceRoot, "xfriend/private.txt").exists())
        val reopened = FriendRunQueue(storage, Any()).runs().first { it.id == first.id }
        val restored = IsolatedAgentState(base, reopened) { _, _ -> }
        assertEquals(first.contextId, reopened.contextId)
        assertEquals(firstState.workspaceRoot.canonicalFile, restored.workspaceRoot.canonicalFile)
        assertEquals(firstState.messages, restored.messages)
        assertEquals("第一项私有结果", restored.messages.getValue("xfriend").last().content)
        assertEquals("第二项私有结果", secondState.messages.getValue("xfriend").last().content)
    }

    @Test fun modelDelegationReturnsAQueuedTaskCardWithoutWaitingForTheChild() = runBlocking {
        val queue = FriendRunQueue(Storage(), Any())
        val root = parent(queue)
        val state = State(temporary.newFolder())
        var calls = 0
        var queuedNotifications = 0
        var card: JSONObject? = null
        val runtime = AgentRuntime(state) { request ->
            val body = payload(request)
            val names = body.getJSONArray("tools").objects().map { it.getJSONObject("function").getString("name") }
            assertTrue(names.containsAll(listOf("spawn_subagent", "get_subagent_result", "cancel_subagent")))
            if (++calls == 1) tool("spawn_subagent", JSONObject().put("task", "独立检查文档").put("title", "检查文档"))
            else {
                val messages = body.getJSONArray("messages")
                card = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                assertEquals("queued", card!!.getString("state"))
                assertEquals("", card!!.getString("result_summary"))
                assertEquals("queued", queue.runs().first { it.id == card!!.getString("task_id") }.state)
                answer("子任务已受理，可继续其他问题")
            }
        }
        runtime.subagents = PersistentSubagentAdapter(queue, root.id, { true }) { queuedNotifications++ }

        assertEquals("子任务已受理，可继续其他问题", runtime.run("xfriend", root.input) {})
        assertEquals(2, calls)
        assertEquals(1, queuedNotifications)
        assertEquals(1, queue.runs().count { it.agentParentId == root.id })
        assertTrue(card!!.getString("context_id").isNotBlank())
        assertEquals(2, queue.runs().first { it.id == root.id }.modelCallsUsed)
    }

    @Test fun askAndPlanChildrenInheritModeAndCannotExecuteProviderRequestedWrites() = runBlocking {
        listOf(ConversationMode.ASK, ConversationMode.PLAN).forEach { mode ->
            val queue = FriendRunQueue(Storage(), Any())
            val root = parent(queue, mode)
            val child = queue.spawnChild(root.id, "xfriend", "只读检查", "child")
            assertEquals(mode, child.conversationMode)
            queue.claimConcurrent()
            val isolated = IsolatedAgentState(State(temporary.newFolder(), mode), child) { messages, steps ->
                queue.saveAgentContext(child.id, messages, steps)
            }
            var calls = 0
            val runtime = AgentRuntime(isolated, conversationMode = child.conversationMode) { request ->
                val body = payload(request)
                val names = body.getJSONArray("tools").objects().map { it.getJSONObject("function").getString("name") }
                assertFalse(names.contains("spawn_subagent"))
                assertFalse(names.contains("write_file"))
                if (++calls == 1) tool("write_file", JSONObject().put("path", "forbidden.txt").put("content", "no"))
                else {
                    val messages = body.getJSONArray("messages")
                    assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").startsWith("工具执行失败："))
                    answer("当前模式已拒绝写入")
                }
            }
            runtime.subagents = PersistentSubagentAdapter(queue, child.id, { true })
            runtime.run("xfriend", child.input) {}
            assertEquals(2, calls)
            assertFalse(File(isolated.workspaceRoot, "xfriend/forbidden.txt").exists())
        }
    }

    @Test fun taskInspectionAndCancellationCannotCrossParentOrSiblingBoundaries() = runBlocking {
        val queue = FriendRunQueue(Storage(), Any())
        val root = parent(queue)
        val unrelated = queue.enqueue("other", "其他父任务", false, "other-parent")
        queue.claimConcurrent()
        val child = queue.spawnChild(root.id, "xfriend", "第一项", "child-1")
        val sibling = queue.spawnChild(root.id, "xfriend", "第二项", "child-2")
        val foreign = queue.spawnChild(unrelated.id, "other", "其他树的子任务", "foreign")
        assertEquals(child.id, queue.claimConcurrent()!!.id)
        val childAdapter = PersistentSubagentAdapter(queue, child.id, { true })
        listOf(root.id, sibling.id, foreign.id).forEach { forbidden ->
            try { childAdapter.result(forbidden); fail("Cross-boundary result was accepted") } catch (_: IllegalArgumentException) { }
            try { childAdapter.cancel(forbidden); fail("Cross-boundary cancel was accepted") } catch (_: IllegalArgumentException) { }
        }
        val rootAdapter = PersistentSubagentAdapter(queue, root.id, { true })
        assertEquals(child.id, rootAdapter.result(child.id).getString("task_id"))
        assertEquals("cancelled", rootAdapter.cancel(child.id).getString("state"))
        assertEquals("queued", queue.runs().first { it.id == sibling.id }.state)
        assertEquals("queued", queue.runs().first { it.id == foreign.id }.state)
    }

    @Test fun revokedAuthorizationBlocksDelegationAndTheNextActualModelRequest() = runBlocking {
        val queue = FriendRunQueue(Storage(), Any())
        val root = parent(queue)
        val adapter = PersistentSubagentAdapter(queue, root.id, { false })
        try { adapter.spawn("xfriend", "request", JSONObject().put("task", "不得受理")); fail() }
        catch (_: CancellationException) { }
        var requests = 0
        val runtime = AgentRuntime(State(temporary.newFolder())) { requests++; answer("不得请求") }
        runtime.subagents = adapter
        try { runtime.run("xfriend", root.input) {}; fail() } catch (_: CancellationException) { }
        assertEquals(0, requests)
        assertEquals(1, queue.runs().size)
        assertEquals(0, queue.runs().single().modelCallsUsed)
    }

    @Test fun exhaustedModelBudgetRejectsBeforeAnyNetworkExecution() = runBlocking {
        val queue = FriendRunQueue(Storage(), Any())
        val root = parent(queue)
        repeat(root.maxModelCalls) { queue.admitModelRequest(root.id, "spent-$it", 1) }
        var requests = 0
        val runtime = AgentRuntime(State(temporary.newFolder())) { requests++; answer("不得请求") }
        runtime.subagents = PersistentSubagentAdapter(queue, root.id, { true })
        try { runtime.run("xfriend", root.input) {}; fail("Expected budget rejection") }
        catch (failure: FriendModelFailure) { assertEquals("AGENT_BUDGET", failure.code) }
        assertEquals(0, requests)
        assertEquals(root.maxModelCalls, queue.runs().single().modelCallsUsed)
    }

    @Test fun crashBeforeDelegationResultCheckpointDoesNotCreateASecondChildOnResume() = runBlocking {
        val queue = FriendRunQueue(Storage(), Any())
        val root = parent(queue)
        val state = State(temporary.newFolder())
        val durable = FriendRunRuntimeCheckpoint(queue, root.id) { true }
        var crash = true
        val checkpoint = object : RuntimeCheckpoint {
            override suspend fun load(): JSONObject? = durable.load()
            override suspend fun save(checkpoint: JSONObject) {
                if (crash && checkpoint.getJSONObject("steps").optJSONObject("expert")?.optInt("tool_index") == 1) {
                    crash = false
                    throw IOException("crash before accepted tool result commits")
                }
                durable.save(checkpoint)
            }
        }
        var modelRequests = 0
        val executor: suspend (Request) -> JSONObject = { request ->
            if (++modelRequests == 1) tool("spawn_subagent", JSONObject().put("task", "只创建一次"))
            else {
                val messages = payload(request).getJSONArray("messages")
                assertEquals("queued", JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getString("state"))
                answer("已受理")
            }
        }
        val first = AgentRuntime(state, checkpoint = checkpoint, requestExecutor = executor)
        first.subagents = PersistentSubagentAdapter(queue, root.id, { true })
        try { first.run("xfriend", root.input) {}; fail() } catch (_: IOException) { }
        val acceptedChild = queue.runs().single { it.agentParentId == root.id }
        queue.recover(); queue.resume(root.id)
        assertEquals(root.id, queue.claimConcurrent()!!.id)
        val resumed = AgentRuntime(state, checkpoint = checkpoint, requestExecutor = executor)
        resumed.subagents = PersistentSubagentAdapter(queue, root.id, { true })

        assertEquals("已受理", resumed.run("xfriend", root.input) {})
        assertEquals(listOf(acceptedChild.id), queue.runs().filter { it.agentParentId == root.id }.map { it.id })
        assertEquals(2, modelRequests)
        assertEquals(2, queue.runs().first { it.id == root.id }.modelCallsUsed)
    }

    @Test fun checkpointedCompleteResponseIsNotRequestedOrReservedAgain() = runBlocking {
        val queue = FriendRunQueue(Storage(), Any())
        val root = parent(queue)
        val state = State(temporary.newFolder())
        val durable = FriendRunRuntimeCheckpoint(queue, root.id) { true }
        var crash = true
        val checkpoint = object : RuntimeCheckpoint {
            override suspend fun load(): JSONObject? = durable.load()
            override suspend fun save(checkpoint: JSONObject) {
                durable.save(checkpoint)
                if (crash && checkpoint.getJSONObject("steps").optJSONObject("expert")?.has("response") == true) {
                    crash = false
                    throw IOException("crash after complete response commits")
                }
            }
        }
        val first = AgentRuntime(state, checkpoint = checkpoint) { answer("已保存的完整回答") }
        first.subagents = PersistentSubagentAdapter(queue, root.id, { true })
        try { first.run("xfriend", root.input) {}; fail() } catch (_: IOException) { }
        assertEquals(1, queue.runs().single().modelCallsUsed)
        queue.recover(); queue.resume(root.id); queue.claimConcurrent()
        val resumed = AgentRuntime(state, checkpoint = checkpoint) { fail("No repeated HTTP call"); answer("bad") }
        resumed.subagents = PersistentSubagentAdapter(queue, root.id, { true })

        assertEquals("已保存的完整回答", resumed.run("xfriend", root.input) {})
        assertEquals(1, queue.runs().single().modelCallsUsed)
        assertEquals(1, state.messages.getValue("xfriend").count { it.role == "assistant" })
    }
}
