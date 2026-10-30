package com.singu.xfriend

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 旧页面仍在使用的两个共享构件，内部已切换到重塑概念稿的组件与令牌，
 * 因此无需逐页改签名即可继承新视觉。
 */

/** 旧名 FamilyStatus → 概念稿的状态胶囊（图标/圆点 + 文本，不只靠颜色区分状态）。 */
@Composable
internal fun FamilyStatus(
    label: String,
    icon: ImageVector? = null,
    tone: String = "neutral",
) {
    XfStatusChip(
        label = label,
        tone = when (tone) {
            "success" -> XfTone.Success
            "warning" -> XfTone.Warning
            "error" -> XfTone.Danger
            "primary" -> XfTone.Accent
            else -> XfTone.Neutral
        },
        icon = icon,
    )
}

/** 旧名 FamilySectionTitle → 概念稿的小节标题（标题 + 右侧文字链接）。 */
@Composable
internal fun FamilySectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    XfSectionHeader(title = title, action = action, onAction = if (action != null) onAction else null)
}
