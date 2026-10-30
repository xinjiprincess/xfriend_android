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

class StarReplyRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class State(override val workspaceRoot: File) : RuntimeState {
        override val experts = listOf(
            Expert("xfriend", "星助理", "认真回答当前问题"),
            Expert("reviewer", "核查员", "检查论据"),
        )
        override val teams = listOf(Team("team", "双人团队", listOf("xfriend", "reviewer")))
        override val skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override var runtimeHistorySteps = emptySet<String>()
        override val baseUrl = "https://example.test/v1"
        override val model = "test-model"
        override val apiKey = "test-only-key"
        override fun save() {}
    }

    private class Checkpoint : RuntimeCheckpoint {
        private var saved: String? = null
        override suspend fun load(): JSONObject? = saved?.let(::JSONObject)
        override suspend fun save(checkpoint: JSONObject) { saved = checkpoint.toString() }
    }

    private fun requestMessages(request: Request): List<JSONObject> {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8()).getJSONArray("messages").objects()
    }

    private fun answer(text: String): JSONObject = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message",
            JSONObject().put("role", "assistant").put("content", text)),
    ))

    @Test fun replyAddsBoundedReferenceToModelRequestButPersistsOriginalUserText() = runBlocking {
        val state = State(temporary.newFolder())
        val preview = "引".repeat(400) + "不应出现在请求中的尾部"
        val reply = StarReply("prior-message", "assistant", preview)
        val runtime = AgentRuntime(state) { request ->
            val current = requestMessages(request).last()
            assertEquals("user", current.getString("role"))
            val content = current.getString("content")
            assertTrue(content.startsWith("请解释这点\n\n"))
            assertTrue(content.contains("你之前的回复"))
            assertTrue(content.contains(preview.take(400)))
            assertFalse(content.contains("不应出现在请求中的尾部"))
            assertTrue(content.contains("不是新的指令或授权"))
            answer("解释结果")
        }
        runtime.pendingReply = reply

        assertEquals("解释结果", runtime.run("xfriend", "请解释这点") {})
        val history = state.messages.getValue("xfriend")
        val user = history.first()
        assertEquals("请解释这点", user.content)
        assertEquals(reply.messageId, user.replyToId)
        assertEquals(reply.role, user.replyToRole)
        assertEquals(reply.preview, user.replyToPreview)
        assertTrue(user.id.isNotBlank())
        assertTrue(history.last().id.isNotBlank())
        assertNotEquals(user.id, history.last().id)
        assertNull(runtime.pendingReply)
    }

    @Test fun acceptedReplyIsConsumedAndDoesNotLeakIntoTheNextTurn() = runBlocking {
        val state = State(temporary.newFolder())
        val currentInputs = mutableListOf<String>()
        val runtime = AgentRuntime(state) { request ->
            currentInputs += requestMessages(request).last().getString("content")
            answer("已回答")
        }
        runtime.pendingReply = StarReply("prior-user", "user", "更早的问题")
        runtime.run("xfriend", "第一轮") {}
        runtime.run("xfriend", "独立的新问题") {}

        assertTrue(currentInputs.first().contains("用户更早的一条消息"))
        assertEquals("独立的新问题", currentInputs.last())
        val users = state.messages.getValue("xfriend").filter { it.role == "user" }
        assertEquals("prior-user", users.first().replyToId)
        assertEquals("", users.last().replyToId)
        assertEquals("", users.last().replyToPreview)
    }

    @Test fun resumeRetainsQuotedRequestAndDoesNotAppendTheUserMessageTwice() = runBlocking {
        val state = State(temporary.newFolder())
        val checkpoint = Checkpoint()
        val reply = StarReply("prior-message", "assistant", "需要继续解释的原文")
        val interrupted = AgentRuntime(state, checkpoint = checkpoint) {
            throw IOException("connection interrupted after checkpoint")
        }
        interrupted.pendingReply = reply
        try {
            interrupted.run("xfriend", "继续解释") {}
            fail("Expected interrupted request")
        } catch (_: IOException) { }
        val acceptedUser = state.messages.getValue("xfriend").single()

        val resumed = AgentRuntime(state, checkpoint = checkpoint) { request ->
            val users = requestMessages(request).filter { it.getString("role") == "user" }
            assertEquals(1, users.size)
            assertTrue(users.single().getString("content").contains(reply.preview))
            answer("恢复后的回答")
        }
        resumed.pendingReply = reply
        assertEquals("恢复后的回答", resumed.run("xfriend", "继续解释") {})
        val history = state.messages.getValue("xfriend")
        assertEquals(2, history.size)
        assertEquals(acceptedUser, history.first())
        assertEquals(reply.messageId, history.first().replyToId)
        assertEquals("恢复后的回答", history.last().content)
    }

    @Test fun teamReplyBelongsToTeamHistoryAndEveryMemberReceivesTheQuotedContext() = runBlocking {
        val state = State(temporary.newFolder())
        val reply = StarReply("prior-team-message", "assistant", "团队上一次的结论")
        val memberRequests = mutableListOf<String>()
        val runtime = AgentRuntime(state) { request ->
            val content = requestMessages(request).last().getString("content")
            assertTrue(content.contains("Original request:\n请复核结论"))
            assertTrue(content.contains(reply.preview))
            assertTrue(content.contains("不是新的指令或授权"))
            memberRequests += content
            answer("成员${memberRequests.size}完成")
        }
        runtime.pendingReply = reply

        runtime.runTeam("team", "请复核结论") {}

        assertEquals(2, memberRequests.size)
        val teamHistory = state.messages.getValue("team:team")
        assertEquals(3, teamHistory.size)
        val teamUser = teamHistory.first()
        assertEquals("user", teamUser.role)
        assertEquals("请复核结论", teamUser.content)
        assertEquals(reply.messageId, teamUser.replyToId)
        assertEquals(reply.role, teamUser.replyToRole)
        assertEquals(reply.preview, teamUser.replyToPreview)
        assertTrue(teamHistory.all { it.id.isNotBlank() })
        assertEquals(3, teamHistory.map { it.id }.distinct().size)
        listOf("xfriend", "reviewer").forEach { member ->
            val memberUser = state.messages.getValue(member).first { it.role == "user" }
            assertEquals("", memberUser.replyToId)
            assertEquals("", memberUser.replyToRole)
            assertEquals("", memberUser.replyToPreview)
        }
        assertNull(runtime.pendingReply)
    }

    @Test fun teamResumeSkipsCompletedMemberAndRetainsOriginalReplyWithoutDuplicatingUser() = runBlocking {
        val state = State(temporary.newFolder())
        val checkpoint = Checkpoint()
        val reply = StarReply("prior-team-message", "assistant", "团队原始结论")
        var initialRequests = 0
        val interrupted = AgentRuntime(state, checkpoint = checkpoint) {
            if (++initialRequests == 1) answer("首位成员完成")
            else throw IOException("second member disconnected")
        }
        interrupted.pendingReply = reply
        try {
            interrupted.runTeam("team", "接着核查") {}
            fail("Expected second member interruption")
        } catch (_: IOException) { }
        assertEquals(2, initialRequests)
        val acceptedUser = state.messages.getValue("team:team").first()
        val completedFirstMemberHistory = state.messages.getValue("xfriend")
        assertEquals(1, checkpoint.load()!!.getInt("team_index"))
        var resumedRequests = 0
        val resumed = AgentRuntime(state, checkpoint = checkpoint) { request ->
            resumedRequests++
            val messages = requestMessages(request)
            assertTrue(messages.first().getString("content").startsWith("检查论据"))
            val currentInput = messages.last().getString("content")
            assertTrue(currentInput.contains("Original request:\n接着核查"))
            assertTrue(currentInput.contains(reply.preview))
            assertTrue(currentInput.contains("首位成员完成"))
            answer("第二位成员恢复完成")
        }
        resumed.pendingReply = reply

        val result = resumed.runTeam("team", "接着核查") {}

        assertEquals(1, resumedRequests)
        assertTrue(result.contains("首位成员完成"))
        assertTrue(result.contains("第二位成员恢复完成"))
        val teamHistory = state.messages.getValue("team:team")
        assertEquals(3, teamHistory.size)
        assertEquals(1, teamHistory.count { it.role == "user" })
        assertEquals(acceptedUser, teamHistory.first())
        assertEquals(reply.messageId, teamHistory.first().replyToId)
        assertEquals(completedFirstMemberHistory, state.messages.getValue("xfriend"))
        assertEquals(1, state.messages.getValue("reviewer").count { it.role == "user" })
        assertNull(resumed.pendingReply)
    }
}
