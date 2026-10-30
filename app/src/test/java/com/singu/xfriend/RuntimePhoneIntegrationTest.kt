package com.singu.xfriend

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

class RuntimePhoneIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private class State(override val workspaceRoot: File, override val conversationMode: String = ConversationMode.CRAFT) : RuntimeState {
        override val experts = listOf(Expert("xfriend", "星助理", "帮助完成当前任务"))
        override val teams = emptyList<Team>()
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override var runtimeHistorySteps = emptySet<String>()
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "test-only-key"
        override fun save() {}
    }

    private class Memory : RuntimeCheckpoint {
        var saved: JSONObject? = null
        var before: ((JSONObject) -> Unit)? = null
        var after: ((JSONObject) -> Unit)? = null
        val commits = mutableListOf<String>()
        override suspend fun load(): JSONObject? = saved?.let { JSONObject(it.toString()) }
        override suspend fun save(checkpoint: JSONObject) {
            before?.invoke(checkpoint)
            val encoded = checkpoint.toString()
            saved = JSONObject(encoded)
            commits += encoded
            after?.invoke(checkpoint)
        }
    }

    private data class Invocation(val name: String, val args: String, val requestId: String)
    private class Phone(private val result: (String, JSONObject, String) -> String) : RuntimePhoneAdapter {
        val invocations = mutableListOf<Invocation>()
        override suspend fun execute(name: String, args: JSONObject, requestId: String): String {
            invocations += Invocation(name, args.toString(), requestId)
            return result(name, args, requestId)
        }
    }

    private fun payload(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
    private fun answer(text: String): JSONObject = response(JSONObject().put("role", "assistant").put("content", text))
    private fun response(message: JSONObject, finish: String = "stop"): JSONObject = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", finish).put("message", message)))
    private fun call(name: String, args: JSONObject = JSONObject(), id: String = "provider-call"): JSONObject = JSONObject()
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun tools(vararg calls: JSONObject): JSONObject = response(JSONObject().put("role", "assistant")
        .put("tool_calls", JSONArray(calls.toList())), "tool_calls")
    private fun step(value: JSONObject): JSONObject? = value.getJSONObject("steps").optJSONObject("expert")
    private fun toolNames(body: JSONObject): Set<String> = body.optJSONArray("tools")?.objects().orEmpty()
        .map { it.getJSONObject("function").getString("name") }.toSet()

    @Test fun craftRoutesPhoneStatusAlarmFileItemAndNotificationToTheBoundAdapter() = runBlocking {
        val state = State(temporary.newFolder())
        val expected = linkedMapOf(
            "phone_status" to JSONObject(),
            "phone_create_alarm" to JSONObject().put("title", "起床").put("rule", "once").put("trigger_at", "2099-11-01T09:00:00+08:00"),
            "phone_save_file" to JSONObject().put("name", "note.txt").put("content", "手机文件内容").put("mime", "text/plain"),
            "phone_create_item" to JSONObject().put("title", "买牛奶").put("details", "低脂"),
            "phone_schedule_task" to JSONObject().put("title", "喝水").put("rule", "daily").put("local_time", "10:00")
                .put("timezone", "Asia/Shanghai").put("task_type", "notification"),
        )
        val phone = Phone { name, _, _ -> JSONObject().put("status", "ok").put("source", name).toString() }
        var requests = 0
        val runtime = AgentRuntime(state) { request ->
            val body = payload(request)
            assertTrue(toolNames(body).containsAll(expected.keys + listOf("phone_read_ui", "phone_ui_action")))
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("phone_status"))
            if (++requests == 1) tools(*expected.entries.mapIndexed { index, entry -> call(entry.key, entry.value, "call-$index") }.toTypedArray())
            else {
                val replies = body.getJSONArray("messages").objects().filter { it.optString("role") == "tool" }
                assertEquals(expected.keys.toList(), replies.map { JSONObject(it.getString("content")).getString("source") })
                assertTrue(replies.all { JSONObject(it.getString("content")).getString("status") == "ok" })
                answer("已根据手机工具返回的结果处理")
            }
        }.also { it.phoneTools = phone }

        assertEquals("已根据手机工具返回的结果处理", runtime.run("xfriend", "设置提醒并保存记录") {})
        assertEquals(2, requests)
        assertEquals(expected.keys.toList(), phone.invocations.map { it.name })
        phone.invocations.forEach { assertEquals(runtimeCanonical(expected.getValue(it.name)), runtimeCanonical(JSONObject(it.args))) }
        assertTrue(phone.invocations.all { it.requestId.isNotBlank() })
        assertEquals(expected.size, phone.invocations.map { it.requestId }.toSet().size)
    }

    @Test fun askAndPlanExposeOnlyPhoneStatusAndBlockUnadvertisedPhoneActions() = runBlocking {
        listOf(ConversationMode.ASK, ConversationMode.PLAN).forEach { mode ->
            val state = State(temporary.newFolder(), mode)
            val phone = Phone { _, _, _ -> fail("A read-only mode reached the phone action adapter"); "{}" }
            var requests = 0
            val runtime = AgentRuntime(state) { request ->
                val body = payload(request)
                assertEquals(setOf("phone_status", "phone_list_items"), toolNames(body).filter { it.startsWith("phone_") }.toSet())
                if (++requests == 1) tools(call("phone_create_alarm", JSONObject().put("title", "不得创建").put("rule", "daily")))
                else {
                    val messages = body.getJSONArray("messages")
                    assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("未确认完成"))
                    answer("请切换执行模式后再安排")
                }
            }.also { it.phoneTools = phone }

            assertEquals("请切换执行模式后再安排", runtime.run("xfriend", "设置闹钟") {})
            assertTrue(phone.invocations.isEmpty())
            assertEquals(mode, state.conversationMode)
            assertEquals(2, requests)
        }
    }

    @Test fun replaySafePhoneToolRetainsItsRequestIdAcrossLostResultCheckpoint() = runBlocking {
        val state = State(temporary.newFolder())
        val memory = Memory()
        memory.before = { checkpoint ->
            if (step(checkpoint)?.optInt("tool_index") == 1) {
                memory.before = null
                throw IOException("lost phone result checkpoint")
            }
        }
        val effects = linkedMapOf<String, String>()
        val phone = Phone { _, args, requestId -> effects.getOrPut(requestId) {
            JSONObject().put("status", "ok").put("item", JSONObject().put("id", "actual-item").put("title", args.getString("title"))).toString()
        } }
        var requests = 0
        val executor: suspend (Request) -> JSONObject = { request ->
            if (++requests == 1) tools(call("phone_create_item", JSONObject().put("title", "仅一次")))
            else {
                val messages = payload(request).getJSONArray("messages")
                assertEquals("actual-item", JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getJSONObject("item").getString("id"))
                answer("事项已保存")
            }
        }
        try {
            AgentRuntime(state, checkpoint = memory, requestExecutor = executor).also { it.phoneTools = phone }.run("xfriend", "记录事项") {}
            fail("Expected the simulated checkpoint failure")
        } catch (_: IOException) { }

        assertEquals("事项已保存", AgentRuntime(state, checkpoint = memory, requestExecutor = executor)
            .also { it.phoneTools = phone }.run("xfriend", "记录事项") {})
        assertEquals(2, phone.invocations.size)
        assertEquals(phone.invocations[0].requestId, phone.invocations[1].requestId)
        assertEquals(1, effects.size)
        assertEquals(2, requests)
        assertEquals(listOf("user", "assistant"), state.messages.getValue("xfriend").map { it.role })
    }

    @Test fun unsafeUiActionLostAfterDispatchRequiresReviewWithoutDispatchingAgain() = runBlocking {
        val state = State(temporary.newFolder())
        val memory = Memory()
        memory.before = { checkpoint ->
            if (step(checkpoint)?.optInt("tool_index") == 1) {
                memory.before = null
                throw IOException("lost UI action result checkpoint")
            }
        }
        val phone = Phone { _, _, _ -> JSONObject().put("status", "action_dispatched").put("requires_observation", true).toString() }
        var requests = 0
        val executor: suspend (Request) -> JSONObject = { requests++; tools(call("phone_ui_action",
            JSONObject().put("snapshot_id", "snapshot-current").put("action", "click").put("node_id", "n1"))) }
        try {
            AgentRuntime(state, checkpoint = memory, requestExecutor = executor).also { it.phoneTools = phone }.run("xfriend", "点击当前按钮") {}
            fail("Expected the simulated checkpoint failure")
        } catch (_: IOException) { }
        assertEquals("phone_ui_action", step(memory.saved!!)!!.getJSONObject("pending").getString("kind"))
        assertEquals(1, phone.invocations.size)

        try {
            AgentRuntime(state, checkpoint = memory, requestExecutor = executor).also { it.phoneTools = phone }.run("xfriend", "点击当前按钮") {}
            fail("An uncertain UI action must require review")
        } catch (failure: FriendModelFailure) { assertEquals("CHECKPOINT_REVIEW", failure.code) }
        assertEquals("requires_review", memory.saved!!.getString("status"))
        assertEquals(1, phone.invocations.size)
        assertEquals(1, requests)
        assertEquals(listOf("user"), state.messages.getValue("xfriend").map { it.role })
    }

    @Test fun committedUnsafeUiIntentWithNoConfirmedDispatchAlsoStopsForReview() = runBlocking {
        val state = State(temporary.newFolder())
        val memory = Memory()
        memory.after = { checkpoint ->
            if (step(checkpoint)?.has("pending") == true) {
                memory.after = null
                throw IOException("crash after intent commit before dispatch")
            }
        }
        val phone = Phone { _, _, _ -> fail("No UI action should be dispatched after an unconfirmed intent"); "{}" }
        var requests = 0
        val executor: suspend (Request) -> JSONObject = { requests++; tools(call("phone_ui_action",
            JSONObject().put("snapshot_id", "snapshot-current").put("action", "back"))) }
        try {
            AgentRuntime(state, checkpoint = memory, requestExecutor = executor).also { it.phoneTools = phone }.run("xfriend", "返回") {}
            fail("Expected the simulated checkpoint failure")
        } catch (_: IOException) { }
        try {
            AgentRuntime(state, checkpoint = memory, requestExecutor = executor).also { it.phoneTools = phone }.run("xfriend", "返回") {}
            fail("Unconfirmed dispatch must not replay")
        } catch (failure: FriendModelFailure) { assertEquals("CHECKPOINT_REVIEW", failure.code) }
        assertTrue(phone.invocations.isEmpty())
        assertEquals(1, requests)
        assertEquals("requires_review", memory.saved!!.getString("status"))
    }

    @Test fun uncertainUiActionResultsStopBeforeAnotherModelRequestWithOrWithoutACheckpoint() = runBlocking {
        val replies = listOf(JSONObject().put("status", "error").toString(),
            JSONObject().put("status", "requires_review").toString(), "not-a-json-result")
        for (reply in replies) for (persist in listOf(false, true)) {
            val state = State(temporary.newFolder())
            val memory = if (persist) Memory() else null
            val phone = Phone { _, _, _ -> reply }
            var requests = 0
            val runtime = AgentRuntime(state, checkpoint = memory) {
                requests++
                tools(call("phone_ui_action", JSONObject().put("snapshot_id", "current-snapshot").put("action", "back")))
            }.also { it.phoneTools = phone }

            try {
                runtime.run("xfriend", "返回") {}
                fail("An uncertain UI result must stop for review")
            } catch (failure: FriendModelFailure) { assertEquals("CHECKPOINT_REVIEW", failure.code) }
            assertEquals(1, phone.invocations.size)
            assertEquals(1, requests)
            assertEquals(listOf("user"), state.messages.getValue("xfriend").map { it.role })
            if (memory != null) {
                assertEquals("requires_review", memory.saved!!.getString("status"))
                assertEquals("phone_ui_action", step(memory.saved!!)!!.getJSONObject("pending").getString("kind"))
            }
        }
    }

    @Test fun uiSnapshotIsVisibleToTheNextModelRequestButNeverPersistedInAnyCheckpoint() = runBlocking {
        val marker = "ephemeral-ui-tree-unique-marker"
        val snapshot = "ephemeral-ui-snapshot-id"
        val state = State(temporary.newFolder())
        val memory = Memory()
        val phone = Phone { name, _, _ ->
            assertEquals("phone_read_ui", name)
            JSONObject().put("status", "ok").put("snapshot_id", snapshot).put("nodes", JSONArray().put(
                JSONObject().put("id", "n1").put("text", marker))).toString()
        }
        var requests = 0
        val runtime = AgentRuntime(state, checkpoint = memory) { request ->
            if (++requests == 1) tools(call("phone_read_ui"))
            else {
                val messages = payload(request).getJSONArray("messages")
                val content = messages.getJSONObject(messages.length() - 1).getString("content")
                assertTrue(content.contains(marker))
                assertTrue(content.contains(snapshot))
                assertTrue(memory.commits.none { it.contains(marker) || it.contains(snapshot) })
                answer("已观察当前界面")
            }
        }.also { it.phoneTools = phone }

        assertEquals("已观察当前界面", runtime.run("xfriend", "查看界面") {})
        assertEquals(2, requests)
        assertEquals(1, phone.invocations.size)
        assertTrue(memory.commits.isNotEmpty())
        assertTrue(memory.commits.none { it.contains(marker) || it.contains(snapshot) })
        val persistedTools = step(memory.saved!!)!!.getJSONArray("conversation").objects().filter { it.optString("role") == "tool" }
        assertEquals(1, persistedTools.size)
        assertTrue(persistedTools.single().getString("content").contains("stale_snapshot"))
        assertFalse(state.messages.toString().contains(marker))
    }
}
