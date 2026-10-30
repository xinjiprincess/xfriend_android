package com.singu.xfriend

/** Restricted, explicit UI intent. Only the current user's text may call this parser. */
internal enum class TaskManagerCommand { SHOW, HIDE, TOGGLE }

internal object TaskManagerIntent {
    private val polite = "(?:请|帮我|麻烦|给我|你)?\\s*"
    private val showVerb = "(?:看看|查看|显示|打开|列出|展示|看一下|看下)"
    private val manager = "(?:任务管理器|任务列表|任务面板|任务树|全部任务|任务)"
    private val show = Regex("^$polite$showVerb\\s*(?:一下|下)?\\s*$manager$")
    private val list = Regex("^$polite(?:(?:我)?(?:现在|都)?(?:有)?哪些\\s*(?:任务|事项|待办)|(?:任务|事项|待办)(?:都)?(?:有)?哪些)$")
    private val listing = Regex("^$polite(?:列出|显示|查看)\\s*(?:所有|全部)?\\s*(?:任务|事项|待办)$")
    private val hideSoft = Regex("^$polite(?:收起|隐藏|折叠|收好)\\s*(?:一下|下)?\\s*(?:任务管理器|任务列表|任务面板|任务树|任务|待办|事项)$")
    private val hideHard = Regex("^$polite(?:关闭|关掉|收起|隐藏)\\s*(?:一下|下)?\\s*(?:任务管理器|任务列表|任务面板|任务树)$")
    private val toggle = Regex("^$polite(?:切换|toggle)\\s*(?:一下|下)?\\s*(?:任务管理器|任务列表|任务面板)$", RegexOption.IGNORE_CASE)

    /**
     * Returns null for ordinary work, references, quotations, web/tool text and ambiguous requests.
     * Repeated SHOW/HIDE stay in the requested state; only an explicit "切换" toggles.
     */
    fun parse(input: String): TaskManagerCommand? {
        val text = input.trim().trimEnd('。', '！', '!', '？', '?', '，', ',', '吧', ' ', '\n')
        if (text.isEmpty() || text.length > 40) return null
        // Quoted/reported speech must not become a UI command.
        if (text.startsWith("他说") || text.startsWith("她说") || text.startsWith("引用") ||
            text.startsWith("网页") || text.startsWith("工具") || text.startsWith("之前") ||
            text.contains("他说") && text.endsWith("任务")) return null
        return when {
            toggle.matches(text) -> TaskManagerCommand.TOGGLE
            hideHard.matches(text) || hideSoft.matches(text) -> TaskManagerCommand.HIDE
            show.matches(text) || list.matches(text) || listing.matches(text) -> TaskManagerCommand.SHOW
            else -> null
        }
    }
}
