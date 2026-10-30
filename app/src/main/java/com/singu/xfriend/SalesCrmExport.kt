package com.singu.xfriend

/** Quoted RFC4180 CSV plus spreadsheet formula neutralisation, including leading whitespace. */
internal fun csvCell(value: String): String {
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
    return "\"" + safe.replace("\"", "\"\"") + "\""
}
internal fun customerCsv(data: SalesData, ids: Set<String>): String {
    val rows = mutableListOf(listOf("客户ID", "姓名", "电话", "公司", "职务", "邮箱", "微信", "来源", "标签", "优先级", "阶段", "下次联系", "备注", "沟通次数", "待办数", "未结商机金额", "已成交金额", "客户画像", "偏好", "需求", "预算", "时间计划", "决策角色", "客户等级"))
    for (c in data.customers.filter { it.id in ids }) {
        val opportunities = data.opportunities.filter { it.customerId == c.id }
        fun amount(won: Boolean) = opportunities.filter { (it.stage == "已成交") == won && it.stage != "已流失" }.fold(java.math.BigDecimal.ZERO) { sum, it -> sum + (it.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) }.toPlainString()
        rows.add(listOf(c.id, c.name, c.phone, c.company, c.role, c.email, c.wechat, c.source, c.tags, c.priority, c.stage, c.nextContact, c.notes,
            data.records.count { it.customerId == c.id }.toString(), data.followUps.count { it.customerId == c.id && !it.done }.toString(), amount(false), amount(true), c.insight.summary, c.insight.preferences, c.insight.needs, c.insight.budget, c.insight.timing, c.insight.decisionRole, c.insight.grade))
    }
    return "\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(",", transform = ::csvCell) } + "\r\n"
}
