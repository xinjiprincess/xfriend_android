package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test

class StarConversationTest {
    @Test fun existingExpertAndTeamHistoriesWithSameResourceIdRemainDistinct() {
        val histories = mapOf(
            "shared" to listOf(Message("assistant", "私人会话")),
            "team:shared" to listOf(Message("assistant", "团队会话")),
        )
        val expert = StarConversation.fromHistoryKey("shared")
        val team = StarConversation.fromHistoryKey("team:shared")

        assertEquals("shared", expert.resourceId)
        assertEquals("shared", team.resourceId)
        assertFalse(expert.team)
        assertTrue(team.team)
        assertEquals("私人会话", histories.getValue(expert.historyKey).single().content)
        assertEquals("团队会话", histories.getValue(team.historyKey).single().content)
    }

    @Test fun quoteRetainsItsSnapshotWhenTheTargetStillExists() {
        val messages = listOf(Message("assistant", "原消息的当前内容", id = "original"))
        assertEquals("发送时的引用摘要", starQuotePreview("original", "发送时的引用摘要", messages))
        assertEquals("原消息的当前内容", starQuotePreview("original", "", messages))
    }

    @Test fun missingTargetShowsPlaceholderWithoutChangingStoredReplyMetadata() {
        val reply = Message("user", "继续", id = "reply", replyToId = "removed",
            replyToRole = "assistant", replyToPreview = "原来的摘要")
        val remaining = listOf(Message("assistant", "另一个消息", id = "other"), reply)

        assertEquals("原消息已不可用", starQuotePreview(reply.replyToId, reply.replyToPreview, remaining))
        assertEquals("removed", reply.replyToId)
        assertEquals("assistant", reply.replyToRole)
        assertEquals("原来的摘要", reply.replyToPreview)
    }

    @Test fun legacyRowsResolveWithTheSameIdsUsedWhenSelectingAReply() {
        val messages = listOf(Message("user", "旧消息"), Message("assistant", "旧回复"))
        assertEquals("旧回复", starQuotePreview(starMessageId(messages[1], 1), "", messages))
        assertEquals("原消息已不可用", starQuotePreview("legacy-2", "不存在的摘要", messages))
    }

    @Test fun identicalLegacyRowsKeepDistinctSelectionAndListKeys() {
        val message = Message("user", "同样的内容")
        assertNotEquals(starMessageId(message, 0), starMessageId(message, 1))
    }
}
