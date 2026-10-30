package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 客户画像只读卡：空缺字段统一显示「待确认」，编辑入口由调用方控制。 */
@Composable internal fun CustomerPortraitCard(insight: CustomerInsight, enabled: Boolean, edit: () -> Unit) {
    val colors = xf
    XfCard {
        XfSectionHeader("客户画像", action = if (enabled) "编辑画像" else null, onAction = if (enabled) edit else null)
        listOf(
            "摘要" to insight.summary,
            "偏好" to insight.preferences,
            "需求" to insight.needs,
            "预算" to insight.budget,
            "时间" to insight.timing,
            "决策角色" to insight.decisionRole,
            "等级" to insight.grade,
        ).forEach { (label, value) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(label, style = XfType.caps, color = colors.ink3)
                Spacer(Modifier.height(2.dp))
                Text(value.ifBlank { "待确认" }, style = XfType.body, color = if (value.isBlank()) colors.ink3 else colors.ink)
            }
        }
    }
}

/**
 * AI 建议卡：所有画像 / 行动 / 商机估计都必须由用户逐项核对后才写入，
 * 这里只提供确认入口，不自动写入任何数据。
 */
@Composable internal fun CustomerAnalysisCard(record: Communication, opportunities: List<Opportunity>, enabled: Boolean,
    onProfile: (CustomerInsight) -> Unit, onAction: (CustomerAction) -> Unit,
    onProbability: (Opportunity) -> Unit, onRecord: () -> Unit,
) {
    val analysis = remember(record.analysisJson) { CustomerAnalysis.read(record.analysisJson) } ?: return
    val colors = xf
    var opportunityId by remember(record.id) { mutableStateOf("") }
    XfSurface(color = colors.surface2, border = true) {
        Column(Modifier.fillMaxWidth().padding(XfDimens.cardPadding), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            XfStatusChip("待确认建议", tone = XfTone.Warning, icon = XfIcons.Sparkles)
            Text(record.title, style = XfType.h3, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("仅在你核对并保存后写入客户资料。", style = XfType.meta, color = colors.ink3)
            if (analysis.profile != CustomerInsight()) {
                XfSurface(color = colors.surface, border = true) {
                    Column(Modifier.fillMaxWidth().padding(XfDimens.cardPaddingCompact), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("画像建议", style = XfType.label, color = colors.ink2)
                        Text(analysis.profile.summary.ifBlank { analysis.profile.needs }.ifBlank { "有新的画像信息待核对" }, style = XfType.small, color = colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        XfButton("核对画像建议", { onProfile(analysis.profile) }, enabled = enabled, kind = XfButtonKind.Secondary, small = true)
                    }
                }
            }
            analysis.actions.forEach { action ->
                XfSurface(color = colors.surface, border = true) {
                    Column(Modifier.fillMaxWidth().padding(XfDimens.cardPaddingCompact), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(action.title, style = XfType.bodyStrong, color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        XfButton("核对并创建待办", { onAction(action) }, enabled = enabled, kind = XfButtonKind.Secondary, small = true)
                    }
                }
            }
            if (analysis.hasProbability) {
                XfSurface(color = colors.surface, border = true) {
                    Column(Modifier.fillMaxWidth().padding(XfDimens.cardPaddingCompact), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("AI 成交估计 ${analysis.low}–${analysis.high}%", style = XfType.bodyStrong, color = colors.ink)
                        Text("基于本次沟通的估计，未经统计校准，不代表承诺。", style = XfType.meta, color = colors.ink3)
                        Text(analysis.reason.ifBlank { "估计理由缺失，需补充核实" }, style = XfType.small, color = colors.ink2)
                        ChoiceRow("应用到商机", listOf("" to "新建商机") + opportunities.map { it.id to it.title }, opportunityId, enabled) { opportunityId = it }
                        XfButton("核对商机估计", {
                            val original = opportunities.find { it.id == opportunityId } ?: Opportunity(customerId = record.customerId, title = record.title)
                            onProbability(original.copy(probabilityLow = analysis.low, probabilityHigh = analysis.high,
                                probabilityReason = analysis.reason, probabilityEvidence = analysis.evidence.joinToString("\n")))
                        }, enabled = enabled && analysis.reason.isNotBlank() && analysis.evidence.isNotEmpty(), kind = XfButtonKind.Secondary, small = true)
                    }
                }
            }
            if (analysis.evidence.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    XfSectionLabel("沟通证据")
                    analysis.evidence.forEach { Text("• $it", style = XfType.small, color = colors.ink2) }
                }
            }
            XfButton("打开完整报告", onRecord, kind = XfButtonKind.Ghost, small = true)
        }
    }
}
