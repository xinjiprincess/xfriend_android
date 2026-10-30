package com.singu.xfriend

/** Store history keys include the team prefix; task resource ids never do. */
internal data class StarConversation(val resourceId: String, val team: Boolean) {
    val historyKey: String get() = if (team) "team:$resourceId" else resourceId

    companion object {
        fun fromHistoryKey(key: String): StarConversation =
            if (key.startsWith("team:")) StarConversation(key.removePrefix("team:"), true)
            else StarConversation(key, false)
    }
}

internal fun starMessageId(message: Message, index: Int): String = message.id.ifBlank { "legacy-$index" }

/** Resolve within this conversation only, while retaining the stored reply relationship. */
internal fun starQuotePreview(targetId: String, snapshot: String, messages: List<Message>): String {
    val target = messages.withIndex().firstOrNull { (index, message) -> starMessageId(message, index) == targetId }?.value
        ?: return "原消息已不可用"
    return snapshot.ifBlank { target.content.take(140) }
}
