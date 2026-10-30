package com.singu.xfriend

/** Stable wire values shared by Store, run ledger and the chat composer. */
object ConversationMode {
    const val ASK = "ask"
    const val PLAN = "plan"
    const val CRAFT = "craft"
    const val MIGRATION_VERSION = 1
    val supported: List<String> = listOf(ASK, PLAN, CRAFT)
    fun requireValid(mode: String): String = mode.also { require(it in supported) { "不支持的会话模式" } }

    /** Ordinary requests execute by default. Explicit per-request read-only intent is handled below. */
    fun defaultFor(sales: Boolean): String = CapabilityPolicy.defaultConversationMode(sales)

    /**
     * One-time migration for installations that persisted the old ASK/PLAN default.
     * The caller must persist [MIGRATION_VERSION] with the migrated value; an in-flight run keeps
     * its own frozen mode and is never silently upgraded.
     */
    fun migrateStored(value: String, storedVersion: Int): String {
        if (storedVersion >= MIGRATION_VERSION) return value.takeIf { it in supported } ?: defaultFor(false)
        return when (value) {
            ASK, PLAN -> CRAFT
            in supported -> value
            else -> defaultFor(false)
        }
    }

    /** Explicit "only discuss / plan first" language constrains this request, not the saved default. */
    fun effectiveForRequest(stored: String, input: String): String {
        val text = input.trim()
        val planOnly = listOf("先规划", "只规划", "仅规划", "先计划", "只计划", "先给方案", "只给方案", "仅给方案", "先出方案", "只做方案")
        val discussOnly = listOf("只讨论", "仅讨论", "先讨论", "只分析", "先分析", "仅分析", "只解释", "仅解释", "不要执行", "别执行", "不要动手", "先不要动手", "只说明", "仅说明")
        return when {
            planOnly.any { text.startsWith(it) } -> PLAN
            discussOnly.any { text.startsWith(it) } -> ASK
            else -> migrateStored(stored, MIGRATION_VERSION)
        }
    }

    fun allowsTool(mode: String, name: String): Boolean = CapabilityPolicy.modeAllows(mode, name)
}
