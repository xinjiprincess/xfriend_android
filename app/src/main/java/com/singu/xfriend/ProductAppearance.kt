package com.singu.xfriend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/** Shared spacing, touch target and card radius used by legacy and new screens. */
internal object ProductAppearance {
    val pagePadding = XfDimens.pagePadding
    val minimumTouchTarget = XfDimens.touch
    val cardRadius = XfDimens.cardRadius
}

internal data class ProductNavigationItem(val id: Int, val label: String, val icon: ImageVector)

/** Apply to the scrolling content behind ProductBottomBar using the same HazeState. */
@Composable
internal fun Modifier.productHazeSource(state: HazeState): Modifier = haze(
    state = state,
    style = HazeDefaults.style(backgroundColor = xf.canvas),
)

/**
 * 重塑后的底栏：毛玻璃表面、顶部细分隔线、选中项为柔和主色胶囊。
 * 保留旧签名，两个产品的根页面无需改动调用点。
 */
@Composable
internal fun ProductBottomBar(
    items: List<ProductNavigationItem>,
    selectedId: Int,
    hazeState: HazeState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = xf
    Column(modifier.fillMaxWidth().hazeChild(state = hazeState)) {
        supportingContent()
        Column(Modifier.fillMaxWidth().background(colors.surface.copy(alpha = .92f))) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Row(
                Modifier.fillMaxWidth().height(XfDimens.bottomBarHeight).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val selected = selectedId == item.id
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(item.id) }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.height(28.dp).widthIn(min = 44.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .then(if (selected) Modifier.background(colors.accentSoft) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (selected) colors.accent else colors.ink3,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            style = XfType.nav,
                            color = if (selected) colors.accent else colors.ink3,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars),
            )
        }
    }
}
