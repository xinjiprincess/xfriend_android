package com.singu.xfriend

/**
 * One authoritative answer to "may this tool be offered and executed right now?".
 *
 * Tool schema filtering, the system prompt and the dispatch guard all use this object so a
 * capability cannot be advertised, described and rejected by three different code paths.
 */
internal data class CapabilityFacts(
    val mode: String,
    val toolsEnabled: Boolean,
    val subagents: Boolean,
    val phone: Boolean,
    val sales: Boolean,
    val disabled: Set<String> = emptySet(),
) {
    val executing: Boolean get() = mode == ConversationMode.CRAFT
}

internal object CapabilityPolicy {
    /** Read-only evidence/task inspection. Available in every mode. */
    val readOnlyTools: Set<String> = setOf(
        "list_workspace", "read_file", "search_knowledge",
        "phone_status", "phone_list_items",
        "list_subagents", "get_subagent_result",
        "read_sales_customer", "read_sales_history", "read_sales_evidence",
    )

    /** Tools with a real side effect. Only the default execution mode may run them. */
    val sideEffectTools: Set<String> = setOf(
        "write_file", "draft_sales_proposal",
        "spawn_subagent", "cancel_subagent",
        "phone_save_file", "phone_create_item", "phone_create_alarm", "phone_schedule_task",
        "phone_cancel_item", "phone_ui_action", "phone_read_ui",
    )

    val knownTools: Set<String> = readOnlyTools + sideEffectTools

    fun modeAllows(mode: String, name: String): Boolean = when (name) {
        in readOnlyTools -> mode in ConversationMode.supported
        in sideEffectTools -> mode == ConversationMode.CRAFT
        else -> false
    }

    fun allows(facts: CapabilityFacts, name: String): Boolean =
        facts.toolsEnabled && name !in facts.disabled && modeAllows(facts.mode, name)

    fun exposed(facts: CapabilityFacts): Set<String> = knownTools.filter { allows(facts, it) }.toSet()

    /** Prompt text derived from the same facts; no capability is invented here. */
    fun instruction(facts: CapabilityFacts): String {
        val exposed = exposed(facts)
        val names = if (exposed.isEmpty()) "无" else exposed.sorted().joinToString(", ")
        val modeLine = when (facts.mode) {
            ConversationMode.CRAFT -> "当前请求为执行请求：参数充分时直接调用已注册工具，再依据工具结果汇报；不要要求用户先切换模式。"
            ConversationMode.PLAN -> "用户明确要求先规划/只讨论方案：可以读取证据，但不产生写入、界面操作或子代理副作用，除非用户随后明确要求执行。"
            else -> "用户明确要求只讨论/解释：可以读取证据，但不产生写入或其他副作用。"
        }
        val phoneLine = if (!facts.toolsEnabled) "工具调用已关闭，本请求不能保存事项、提醒或操作文件；如实说明并给出用户可手动开启的位置。"
        else if (facts.phone) {
            "本机事项、日期型待办和提醒是真实持久记录。phone_create_item 可用 due_date 保存没有具体时刻的日期事项；未要求通知时不要编造提醒时间。用户问以前交代的事项时先调用 phone_list_items 查询当前账户记录，不要仅凭最近聊天窗口回答。phone_schedule_task 目前只发本机通知，不会到点自动运行模型或联网查价。"
        } else "本请求没有注册手机本机工具，不要声称已保存事项或提醒。"
        val subagentLine = if (!facts.toolsEnabled) "工具调用已关闭，不能创建或查询子代理。"
        else if (facts.subagents) "可创建真实子代理；返回任务 ID 不代表任务完成。" else "本请求没有注册子代理工具。"
        val toolsLine = if (!facts.toolsEnabled) "用户关闭了工具调用，本请求没有可执行工具；如实说明并给出开启位置。" else "本请求实际可调用工具：$names。"
        return "$modeLine\n$toolsLine\n$phoneLine\n$subagentLine\n" +
            "只依据本次工具结果判断能力。不得笼统声称“只读”“没有写权限”“没有跨会话记录”，" +
            "除非本次能力清单和失败结果确实支持该结论。未成功调用的动作不得说已完成。\n" +
            "本软件自身配置（供应商、密钥、账号/激活、权限开关、默认策略、专家/技能、授权存储、安装卸载）" +
            "只能由用户手动修改。不得通过工具、子代理、文件路径、浏览器/Intent、深链接或无障碍点击绕过；" +
            "被拦截时返回 APP_CONFIGURATION_PROTECTED 并说明手动入口。创建事项、取消指定任务、显示/隐藏任务管理器不是配置变更。"
    }

    fun defaultConversationMode(@Suppress("UNUSED_PARAMETER") sales: Boolean): String = ConversationMode.CRAFT
}
