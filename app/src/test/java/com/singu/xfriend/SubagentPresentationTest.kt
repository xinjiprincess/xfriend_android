package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test

class SubagentPresentationTest {
    private fun run(id: String, resource: String = "main", team: Boolean = false, parent: String? = null,
        context: String = "", state: String = "queued", created: Long = 1) = FriendRun(
        id, resource, "任务 $id", team, "request-$id", state = state, created = created,
        contextId = context, agentParentId = parent, agentDepth = if (parent == null) 0 else 1,
    )

    @Test fun conversationCardsFollowTheAgentGraphWithoutMixingTeamOrPrivateTasks() {
        val privateRoot = run("private")
        val teamRoot = run("team", team = true)
        val specialist = run("specialist", resource = "reviewer", parent = privateRoot.id, context = "ctx-1")
        val nested = run("nested", resource = "researcher", parent = specialist.id, context = "ctx-2")
        val teamChild = run("team-child", parent = teamRoot.id, context = "ctx-team")
        val runs = listOf(nested, teamChild, privateRoot, specialist, teamRoot)

        assertEquals(setOf("private", "specialist", "nested"), subagentConversationTasks(runs, "main", false).map { it.id }.toSet())
        assertEquals(setOf("team", "team-child"), subagentConversationTasks(runs, "main", true).map { it.id }.toSet())
    }

    @Test fun queuedFollowupDoesNotHideTheRunningAttemptFromTheTaskTray() {
        val running = run("running", context = "ctx", state = "running", created = 1)
        val queued = run("queued", context = "ctx", created = 2)
        val unrelated = run("root", created = 3)

        assertEquals(setOf("running", "root"), subagentTaskRows(listOf(running, queued, unrelated)).map { it.id }.toSet())
        assertEquals("queued", subagentTaskRows(listOf(running.copy(state = "completed"), queued)).single().id)
        assertEquals("queued", subagentTaskRows(listOf(running.copy(state = "completed"), queued.copy(state = "completed"))).single().id)
    }

    @Test fun cancellationDescendantsUseAgentParentsRatherThanTheRetryAttemptChain() {
        val root = run("root")
        val child = run("child", parent = root.id, context = "ctx-child")
        val nested = run("nested", parent = child.id, context = "ctx-nested")
        val retry = run("retry").copy(parentId = root.id)

        assertEquals(setOf(child.id, nested.id), subagentDescendants(root, listOf(root, child, nested, retry)).map { it.id }.toSet())
    }

    @Test fun cancelledAndUncertainContextsCannotBeSelectedForFollowup() {
        val child = run("child", context = "ctx", state = "completed")
        assertTrue(subagentCanTarget(child))
        assertFalse(subagentCanTarget(child.copy(agentCancelled = true)))
        assertFalse(subagentCanTarget(child.copy(state = "cancelled")))
        assertFalse(subagentCanTarget(child.copy(state = "requires_review")))
        assertFalse(subagentCanTarget(run("main")))
        assertEquals(SubagentTaskGroup.NeedsYou, subagentTaskGroup(child.copy(state = "requires_review")))
        assertEquals(SubagentTaskGroup.Waiting, subagentTaskGroup(child.copy(state = "waiting_device")))
    }

    @Test fun followupBudgetIncludesEarlierInputsAndNestedAgentsInsteadOfResettingToZero() {
        val first = run("first", parent = "root", context = "ctx", state = "completed")
            .copy(modelCallsUsed = 2, tokensReserved = 4000)
        val followup = run("next", parent = "root", context = "ctx", created = 2)
            .copy(parentId = first.id)
        val nested = run("nested", parent = first.id, context = "ctx-nested")
            .copy(modelCallsUsed = 1, tokensReserved = 2000)
        val unrelated = run("unrelated", parent = "other", context = "ctx-other")
            .copy(modelCallsUsed = 5, tokensReserved = 10000)
        val runs = listOf(first, followup, nested, unrelated)

        assertEquals(setOf(first.id, followup.id, nested.id), subagentBudgetMembers(followup, runs).map { it.id }.toSet())
        assertEquals("累计调用 3/8 · 累计预留 6000/64000 tok", subagentTaskBudget(followup, runs))
    }
}
