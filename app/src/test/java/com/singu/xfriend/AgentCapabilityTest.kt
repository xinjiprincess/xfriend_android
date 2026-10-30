package com.singu.xfriend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentCapabilityTest {
    @Test fun bothProductsDefaultToExecutionAndLegacyModesMigrateOnce() {
        assertEquals("craft", ConversationMode.defaultFor(false))
        assertEquals("craft", ConversationMode.defaultFor(true))
        assertEquals("craft", ConversationMode.migrateStored("ask", 0))
        assertEquals("craft", ConversationMode.migrateStored("plan", 0))
        assertEquals("ask", ConversationMode.migrateStored("ask", ConversationMode.MIGRATION_VERSION))
        assertEquals("craft", ConversationMode.migrateStored("illegal", 0))
        assertEquals("craft", ConversationMode.migrateStored("illegal", 9))
    }

    @Test fun explicitDiscussionOrPlanAppliesOnlyToThatRequest() {
        assertEquals("ask", ConversationMode.effectiveForRequest("craft", "只讨论一下这个方案，不要执行"))
        assertEquals("plan", ConversationMode.effectiveForRequest("craft", "先规划步骤，别动手"))
        assertEquals("craft", ConversationMode.effectiveForRequest("craft", "帮我记住明天问Mac价格"))
        assertEquals("ask", ConversationMode.effectiveForRequest("ask", "普通问题"))
    }

    @Test fun sameCapabilitySourceControlsPromptExposureAndExecution() {
        val craft = CapabilityFacts("craft", true, subagents = true, phone = true, sales = false)
        assertTrue(CapabilityPolicy.allows(craft, "phone_create_item"))
        assertTrue(CapabilityPolicy.allows(craft, "write_file"))
        assertTrue(CapabilityPolicy.allows(craft, "spawn_subagent"))
        val ask = craft.copy(mode = "ask")
        assertTrue(CapabilityPolicy.allows(ask, "phone_status"))
        assertTrue(CapabilityPolicy.allows(ask, "phone_list_items"))
        assertFalse(CapabilityPolicy.allows(ask, "phone_create_item"))
        assertFalse(CapabilityPolicy.allows(ask, "spawn_subagent"))
        assertFalse(CapabilityPolicy.allows(ask, "phone_ui_action"))
        val off = craft.copy(toolsEnabled = false)
        assertTrue(CapabilityPolicy.exposed(off).isEmpty())
        val prompt = CapabilityPolicy.instruction(craft)
        assertTrue(prompt.contains("phone_create_item"))
        assertTrue(prompt.contains("APP_CONFIGURATION_PROTECTED"))
        assertTrue(prompt.contains("不会到点自动运行模型"))
        assertFalse(CapabilityPolicy.allows(craft, "unknown_tool"))
    }

    @Test fun appOwnConfigurationIsProtectedForBothFlavoursAndSuffixes() {
        val pkg = "com.singu.xfriend.mobile.debug"
        listOf("com.singu.xfriend.mobile", "com.singu.xfriend.mobile.debug", "com.singu.xfriend.mobile.qa",
            "com.singu.xsalers.mobile", "com.singu.xsalers.mobile.debug", "com.singu.xsalers.mobile.qa").forEach {
            assertTrue("$it should be protected", AppConfigProtection.isSelfPackage(pkg, it))
        }
        assertFalse(AppConfigProtection.isSelfPackage(pkg, "com.example.notes"))
        assertTrue(AppConfigProtection.isProtectedScreen(pkg, "com.singu.xfriend.mobile.qa"))
        assertTrue(AppConfigProtection.isProtectedScreen(pkg, "com.android.settings"))
        assertFalse(AppConfigProtection.isProtectedScreen(pkg, "com.example.notes"))
        assertTrue(AppConfigProtection.isProtectedUri(pkg, "package:com.singu.xfriend.mobile.debug"))
        assertTrue(AppConfigProtection.isProtectedUri(pkg, "intent://settings"))
        assertTrue(AppConfigProtection.isProtectedUri(pkg, "file:///data/data/com.singu.xfriend.mobile/files/xfriend.json"))
        assertFalse(AppConfigProtection.isProtectedUri(pkg, "https://example.com/help"))
    }

    @Test fun agentWorkspacePathsCannotReachConfiguration() {
        val root = File("/tmp/xfriend-workspace/expert")
        assertTrue(AppConfigProtection.isProtectedAgentPath(root, File(root, "xfriend.json")))
        assertTrue(AppConfigProtection.isProtectedAgentPath(root, File(root, "no_backup/credentials")))
        assertFalse(AppConfigProtection.isProtectedAgentPath(root, File(root, "notes.txt")))
        assertTrue(AppConfigProtection.isProtectedAgentPath(root, File(root, "../outside.txt")))
    }

    @Test fun taskManagerCommandIsRestrictedAndIdempotent() {
        assertEquals(TaskManagerCommand.SHOW, TaskManagerIntent.parse("看看任务"))
        assertEquals(TaskManagerCommand.SHOW, TaskManagerIntent.parse("帮我查看任务管理器。"))
        assertEquals(TaskManagerCommand.SHOW, TaskManagerIntent.parse("任务都有哪些"))
        assertEquals(TaskManagerCommand.HIDE, TaskManagerIntent.parse("收起任务管理器"))
        assertEquals(TaskManagerCommand.HIDE, TaskManagerIntent.parse("隐藏任务"))
        assertEquals(TaskManagerCommand.TOGGLE, TaskManagerIntent.parse("切换任务管理器"))
        assertNull(TaskManagerIntent.parse("他说收起任务"))
        assertNull(TaskManagerIntent.parse("网页里写着：打开任务管理器"))
        assertNull(TaskManagerIntent.parse("帮我看看任务并取消第一个"))
        assertNull(TaskManagerIntent.parse("继续分析这个任务"))

        // SHOW/HIDE are states, not toggles; only TOGGLE flips the state.
        var visible = false
        visible = when (TaskManagerIntent.parse("查看任务")!!) { TaskManagerCommand.SHOW -> true; TaskManagerCommand.HIDE -> false; TaskManagerCommand.TOGGLE -> !visible }
        visible = when (TaskManagerIntent.parse("查看任务")!!) { TaskManagerCommand.SHOW -> true; TaskManagerCommand.HIDE -> false; TaskManagerCommand.TOGGLE -> !visible }
        assertTrue(visible)
        visible = when (TaskManagerIntent.parse("隐藏任务")!!) { TaskManagerCommand.SHOW -> true; TaskManagerCommand.HIDE -> false; TaskManagerCommand.TOGGLE -> !visible }
        visible = when (TaskManagerIntent.parse("隐藏任务")!!) { TaskManagerCommand.SHOW -> true; TaskManagerCommand.HIDE -> false; TaskManagerCommand.TOGGLE -> !visible }
        assertFalse(visible)
    }

    @Test fun unifiedProjectionKeepsSourceAndIdAndNeverFabricatesState() {
        val run = FriendRun("00000000-0000-0000-0000-000000000001", "e", "任务", false, "r1",
            state = "running", created = 10, updated = 20, requestMessageId = "m1")
        val phone = JSONObject().put("id", "p-1").put("kind", "item").put("title", "问Mac价格")
            .put("status", "open").put("due_date", "2026-10-30").put("created_at", 5)
        val tasks = UnifiedTaskProjection.merge(listOf(run), listOf(phone))
        assertEquals(2, tasks.size)
        val execution = tasks.first { it.source == UnifiedTaskProjection.SOURCE_RUN }
        assertEquals(run.id, execution.id); assertTrue(execution.active)
        val item = tasks.first { it.source == UnifiedTaskProjection.SOURCE_PHONE }
        assertEquals("p-1", item.id)
        assertTrue(item.detail.contains("2026-10-30"))
        assertTrue(UnifiedTaskProjection.filter(tasks, "进行中").any { it.id == execution.id })
        assertEquals(1, UnifiedTaskProjection.page(tasks, 0, 1).size)
        assertEquals(1, UnifiedTaskProjection.page(tasks, 1, 1).size)
        assertTrue(UnifiedTaskProjection.page(tasks, 9, 1).isEmpty())
    }

    @Test fun inboxKeepsFailedInputAndBindsLaterRunResult() {
        val failed = InboxEntry("r1", "不得丢失的文字", "e", false, state = "提交失败", retryable = true)
        val inbox = SubmissionInbox.upsert(emptyList(), failed)
        assertEquals("不得丢失的文字", inbox.single().input)
        assertTrue(inbox.single().retryable)
        val run = FriendRun("00000000-0000-0000-0000-000000000002", "e", failed.input, false, "r1",
            state = "completed", requestMessageId = "r1")
        val merged = SubmissionInbox.mergeRuns(inbox, listOf(run))
        assertEquals("已完成", merged.single().state)
        assertEquals(run.id, merged.single().runId)
        val failedRun = run.copy(id = "00000000-0000-0000-0000-000000000003", state = "failed", errorMessage = "preflight_failed")
        val failedAgain = SubmissionInbox.mergeRuns(inbox, listOf(failedRun))
        assertTrue(failedAgain.single().retryable)
        assertTrue(failedAgain.single().message.contains("preflight_failed"))
    }

    @Test fun isolatedRootResultsStaySeparateAndAreMergeableById() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val a = q.enqueue("e", "A", false, "a", historySnapshot = listOf(Message("user", "之前")))
        val b = q.enqueue("e", "B", false, "b", historySnapshot = listOf(Message("user", "之前")))
        assertNotEquals(a.rootContextId, b.rootContextId)
        assertEquals("", a.contextId); assertNull(a.agentParentId)
        assertEquals(a.id, q.claimConcurrent()!!.id)
        assertEquals(b.id, q.claimConcurrent()!!.id)
        assertNull(q.claimConcurrent())
        q.saveRootContext(a.id, mapOf("e" to listOf(Message("user", "A"), Message("assistant", "只属于A"))), setOf("s1"))
        q.saveRootContext(b.id, mapOf("e" to listOf(Message("user", "B"), Message("assistant", "只属于B"))), setOf("s2"))
        assertEquals("只属于A", q.runs().first { it.id == a.id }.agentMessages.getValue("e").last().content)
        assertEquals("只属于B", q.runs().first { it.id == b.id }.agentMessages.getValue("e").last().content)
        val reopened = FriendRunCodec.decode(FriendRunCodec.encode(q.runs()))
        assertEquals(q.runs(), reopened)
    }

    @Test fun outOfOrderCompletionsKeepRepliesBoundToTheirOwnRequest() {
        var turns = listOf(
            Message("user", "A", "req-A"),
            Message("user", "B", "req-B"),
        )
        val a = FriendRun("00000000-0000-0000-0000-00000000000a", "e", "A", false, "req-A",
            state = "completed", partial = "回答A", requestMessageId = "req-A", rootContextId = "00000000-0000-0000-0000-0000000000aa")
        val b = FriendRun("00000000-0000-0000-0000-00000000000b", "e", "B", false, "req-B",
            state = "completed", partial = "回答B", requestMessageId = "req-B", rootContextId = "00000000-0000-0000-0000-0000000000bb")
        turns = RootTurnMerge.merge(turns, b)
        turns = RootTurnMerge.merge(turns, a)
        assertEquals(listOf("A", "B", "回答B", "回答A"), turns.map { it.content })
        assertEquals("req-B", turns.first { it.content == "回答B" }.replyToId)
        assertEquals("req-A", turns.first { it.content == "回答A" }.replyToId)
        assertEquals(turns, RootTurnMerge.merge(turns, a))
    }

    @Test fun smallWindowCommandOnlyAcceptsExplicitCurrentUserRequests() {
        assertTrue(SmallWindowIntent.matches("小窗"))
        assertTrue(SmallWindowIntent.matches("打开小窗模式"))
        assertTrue(SmallWindowIntent.matches("进入画中画"))
        assertFalse(SmallWindowIntent.matches("他说打开小窗"))
        assertFalse(SmallWindowIntent.matches("网页里写着进入小窗"))
        assertFalse(SmallWindowIntent.matches("把这个窗口改小并总结"))
    }

    @Test fun rootRequestsHaveUsableBudgetButDelegatedChildrenStayBounded() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val run = q.enqueue("e", "比较成都和重庆的 GDP", false, "gdp", historySnapshot = emptyList())
        assertEquals(SubagentPolicy.ROOT_MAX_MODEL_CALLS, run.maxModelCalls)
        assertEquals(SubagentPolicy.ROOT_TOKEN_BUDGET, run.tokenBudget)
        q.claimConcurrent()
        repeat(4) { q.admitModelRequest(run.id, "root-$it", 120_000) }
        assertEquals(480_000, q.runs().single().tokensReserved)
        assertThrows(IllegalArgumentException::class.java) { FriendRunCodec.decode(FriendRunCodec.encode(listOf(run.copy(maxModelCalls = 99)))) }
    }

    @Test fun retriedRootGetsFreshRootBudgetInsteadOfInheritingOldAttempt() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        val old = q.enqueue("e", "GDP", false, "old", historySnapshot = emptyList()); q.claimConcurrent()
        repeat(SubagentPolicy.ROOT_MAX_MODEL_CALLS) { q.admitModelRequest(old.id, "old-$it", 1) }
        assertThrows(FriendModelFailure::class.java) { q.admitModelRequest(old.id, "old-over", 1) }
        q.recover()
        val fresh = q.enqueue("e", "GDP", false, "fresh", old.id, historySnapshot = emptyList())
        assertEquals(SubagentPolicy.ROOT_MAX_MODEL_CALLS, fresh.maxModelCalls)
        assertEquals(fresh.id, q.claimConcurrent()!!.id)
        q.admitModelRequest(fresh.id, "fresh-1", 1)
        assertEquals(1, q.runs().first { it.id == fresh.id }.modelCallsUsed)
    }

    @Test fun sameResourceLegacyRootsRemainSerialized() {
        val q = FriendRunQueue(FriendRunMemory(), Any())
        q.enqueue("e", "A", false, "a")
        q.enqueue("e", "B", false, "b")
        assertNotNull(q.claimConcurrent())
        assertNull(q.claimConcurrent())
    }
}
