package com.singu.xfriend

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 外观偏好：主题模式（跟随系统/浅色/深色）与主色（概念稿 4 色 + XSalers 品牌紫）。
 *
 * 状态是本机 SharedPreferences，不进账号同步；修改后整棵 Compose 树会立即换肤。
 */
internal object XfAppearance {
    private const val PREFS = "xf_appearance"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"

    private var prefs: SharedPreferences? = null
    private var initialized = false

    var mode by mutableStateOf(XfThemeMode.SYSTEM)
        private set

    var accent by mutableStateOf(defaultAccent())
        private set

    fun initialize(context: Context) {
        if (initialized) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        mode = XfThemeMode.restore(store.getString(KEY_MODE, null))
        accent = XfAccent.restore(store.getString(KEY_ACCENT, null)) ?: defaultAccent()
        initialized = true
    }

    fun updateMode(value: XfThemeMode) {
        mode = value
        prefs?.edit()?.putString(KEY_MODE, value.id)?.apply()
    }

    fun updateAccent(value: XfAccent) {
        accent = value
        prefs?.edit()?.putString(KEY_ACCENT, value.id)?.apply()
    }

    private fun defaultAccent(): XfAccent =
        if (ProductBrand.isSales) XfAccent.VIOLET else XfAccent.INDIGO
}

/** 设置页里的外观面板：主题模式、主色选择与实时预览。 */
@Composable
internal fun XfAppearancePanel(modifier: Modifier = Modifier) {
    val colors = xf
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        XfCard {
            Text("主题模式", style = XfType.h3, color = colors.ink)
            Spacer(Modifier.height(2.dp))
            Text("跟随系统，或手动固定为浅色 / 深色。", style = XfType.meta, color = colors.ink3)
            Spacer(Modifier.height(12.dp))
            XfSegmented(
                options = XfThemeMode.entries.map { it.label },
                selectedIndex = XfThemeMode.entries.indexOf(XfAppearance.mode),
                onSelect = { XfAppearance.updateMode(XfThemeMode.entries[it]) },
            )
        }
        XfCard {
            Text("主色", style = XfType.h3, color = colors.ink)
            Spacer(Modifier.height(2.dp))
            Text("同一主色贯穿图标、按钮与选中态，其余颜色保持中性。", style = XfType.meta, color = colors.ink3)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                XfAccent.entries.forEach { option ->
                    XfAccentChoice(option)
                }
            }
        }
        XfCard {
            Text("预览", style = XfType.h3, color = colors.ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                XfStatusChip("运行中", tone = XfTone.Accent)
                XfStatusChip("已完成", tone = XfTone.Success)
                XfStatusChip("需处理", tone = XfTone.Warning)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XfButton("主要操作", {}, small = true)
                XfButton("次要操作", {}, kind = XfButtonKind.Secondary, small = true)
            }
            Spacer(Modifier.height(12.dp))
            XfListRow(
                title = "示例列表项",
                subtitle = "暖纸色画布 · 白色卡片 · 单一主色",
                leading = { XfIconTile(XfIcons.Sparkles, size = 34.dp) },
                trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
            )
        }
        Text(
            "外观偏好只保存在本机，不写入账号同步；字号与对比度继续跟随系统无障碍设置。",
            style = XfType.meta,
            color = colors.ink3,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun XfAccentChoice(option: XfAccent) {
    val colors = xf
    val selected = option == XfAppearance.accent
    val swatch = XfPalettes.resolve(colors.isDark, option).accent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { XfAppearance.updateAccent(option) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .then(if (selected) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier)
                .padding(if (selected) 4.dp else 3.dp)
                .clip(CircleShape)
                .background(swatch),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(XfIcons.Check, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            option.label,
            style = XfType.meta,
            color = if (selected) colors.ink else colors.ink3,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
