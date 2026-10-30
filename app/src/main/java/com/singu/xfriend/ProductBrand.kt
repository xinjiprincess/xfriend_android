package com.singu.xfriend

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 产品标识。品牌色板由 [XfAppearance] 的 accent 决定：
 * XFriend 默认靛蓝（概念稿主色），XSalers 默认品牌紫罗兰；两者共享同一套令牌与组件。
 */
internal object ProductBrand {
    val isSales: Boolean = BuildConfig.SALES_APP
    val name: String = if (isSales) "XSalers" else "XFriend"
    val icon: Int = if (isSales) R.drawable.xsalers_icon else R.drawable.xfriend_icon
    val defaultAccent: XfAccent = if (isSales) XfAccent.VIOLET else XfAccent.INDIGO

    /** 品牌主色的静态留档值，仅供非 Compose 代码或兼容用途使用。 */
    val primary: Color = if (isSales) Color(0xFF7055DE) else Color(0xFF4F5DF0)
    val secondary: Color = primary
}

/**
 * 兼容入口：旧页面（MainActivity、SalesRoot）仍调用 [ProductTheme]。
 * 实际主题由 MainActivity 初始化并读取 [XfAppearance]，这里只是转接到 [XfTheme]。
 */
@Composable
internal fun ProductTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_PARAMETER") val ignored = darkTheme
    XfTheme(content = content)
}
