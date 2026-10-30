package com.singu.xfriend

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Singu Xfriend 前端重塑概念稿 v1 的 Android 令牌层。
 *
 * 设计方向 "Quiet cockpit"：暖纸色画布、单一主色、统一线性图标语言、星形品牌标识；
 * 提供亮/暗两套语义色与 5 组主色（概念稿 4 组 + XSalers 品牌紫）。
 * 页面不应再直接写 Color(0x...) 或自定义字号，统一从这里取值。
 *
 * 数值来源见 D:\xfriend-suite\docs\design\redesign-concept-android-spec.md（§1 令牌 / §2 排版尺寸）。
 */

internal enum class XfAccent(val id: String, val label: String) {
    INDIGO("indigo", "靛蓝"),
    TEAL("teal", "青绿"),
    AMBER("amber", "琥珀"),
    ROSE("rose", "玫瑰"),
    VIOLET("violet", "紫罗兰");

    companion object {
        fun restore(id: String?): XfAccent? = entries.firstOrNull { it.id == id }
    }
}

internal enum class XfThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun restore(id: String?): XfThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** One accent palette, resolved for light or dark. */
internal data class XfAccentColors(
    val accent: Color,
    val solid: Color,
    val ink: Color,
    val soft: Color,
    val line: Color,
)

/**
 * Semantic tokens from the concept stylesheet plus the derived semantic containers /
 * readable text colors (spec §1.5). Screens must never hardcode hex values.
 */
internal data class XfColors(
    val isDark: Boolean,
    val canvas: Color,
    val canvas2: Color,
    val sidebar: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val blue: Color,
    val accent: Color,
    val accentSolid: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val overlay: Color,
    val successContainer: Color,
    val warningContainer: Color,
    val dangerContainer: Color,
    val infoContainer: Color,
    val successText: Color,
    val warningText: Color,
    val dangerText: Color,
    val infoText: Color,
) {
    val success: Color get() = green
    val warning: Color get() = amber
    val danger: Color get() = red
    val info: Color get() = blue
    val successSoft: Color get() = successContainer
    val warningSoft: Color get() = warningContainer
    val dangerSoft: Color get() = dangerContainer
    val infoSoft: Color get() = infoContainer
    val neutralSoft: Color get() = surface2
}

internal object XfPalettes {
    // Light: warm paper canvas, white cards, one strong accent.
    val lightBase = XfColors(
        isDark = false,
        canvas = Color(0xFFF6F5F1),
        canvas2 = Color(0xFFEFEDE7),
        sidebar = Color(0xFFEFEDE7),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFF7F6F2),
        surface3 = Color(0xFFEEECE6),
        line = Color(0xFFE5E2DA),
        lineStrong = Color(0xFFD6D2C7),
        ink = Color(0xFF1B1C1F),
        ink2 = Color(0xFF57585F),
        ink3 = Color(0xFF8C8D94),
        ink4 = Color(0xFFB6B7BD),
        green = Color(0xFF1F9D6B),
        amber = Color(0xFFC27C0E),
        red = Color(0xFFD5443B),
        blue = Color(0xFF3B82F6),
        accent = Color(0xFF4F5DF0),
        accentSolid = Color(0xFF4351E8),
        accentInk = Color(0xFF3B47CC),
        accentSoft = Color(0xFFECEEFE),
        accentLine = Color(0xFFD9DEFB),
        overlay = Color(0x4714120C),
        successContainer = Color(0xFFE9F5F0),
        warningContainer = Color(0xFFF7EEE0),
        dangerContainer = Color(0xFFFBECEB),
        infoContainer = Color(0xFFEBF2FE),
        successText = Color(0xFF17754F),
        warningText = Color(0xFF8A5600),
        dangerText = Color(0xFFC0342C),
        infoText = Color(0xFF2563EB),
    )

    val darkBase = XfColors(
        isDark = true,
        canvas = Color(0xFF0E0F12),
        canvas2 = Color(0xFF131418),
        sidebar = Color(0xFF121317),
        surface = Color(0xFF181A1F),
        surface2 = Color(0xFF1E2026),
        surface3 = Color(0xFF24262D),
        line = Color(0xFF282A31),
        lineStrong = Color(0xFF373A43),
        ink = Color(0xFFF1F2F4),
        ink2 = Color(0xFFAAAEB8),
        ink3 = Color(0xFF7B7F89),
        ink4 = Color(0xFF565A64),
        green = Color(0xFF37C08A),
        amber = Color(0xFFE0A33A),
        red = Color(0xFFF06B62),
        blue = Color(0xFF6FA8FF),
        accent = Color(0xFF8B95FF),
        accentSolid = Color(0xFF4F5DF0),
        accentInk = Color(0xFFA6AEFF),
        accentSoft = Color(0x218B95FF),
        accentLine = Color(0x478B95FF),
        overlay = Color(0x80000000),
        successContainer = Color(0xFF1C312E),
        warningContainer = Color(0xFF342D23),
        dangerContainer = Color(0xFF3B272A),
        infoContainer = Color(0xFF242E3E),
        successText = Color(0xFF37C08A),
        warningText = Color(0xFFE0A33A),
        dangerText = Color(0xFFF06B62),
        infoText = Color(0xFF6FA8FF),
    )

    private val lightAccents: Map<XfAccent, XfAccentColors> = mapOf(
        XfAccent.INDIGO to XfAccentColors(
            accent = Color(0xFF4F5DF0), solid = Color(0xFF4351E8), ink = Color(0xFF3B47CC),
            soft = Color(0xFFECEEFE), line = Color(0xFFD9DEFB),
        ),
        XfAccent.TEAL to XfAccentColors(
            accent = Color(0xFF0E9384), solid = Color(0xFF0B7F73), ink = Color(0xFF0A7568),
            soft = Color(0xFFE4F6F2), line = Color(0xFFC6EDE5),
        ),
        XfAccent.AMBER to XfAccentColors(
            accent = Color(0xFFB26B00), solid = Color(0xFF996000), ink = Color(0xFF8F5600),
            soft = Color(0xFFFCF1DF), line = Color(0xFFF4DFB9),
        ),
        XfAccent.ROSE to XfAccentColors(
            accent = Color(0xFFD94F6B), solid = Color(0xFFC74059), ink = Color(0xFFB33B54),
            soft = Color(0xFFFDECEF), line = Color(0xFFF8D3DB),
        ),
        // XSalers keeps its violet brand identity while sharing the same token system.
        XfAccent.VIOLET to XfAccentColors(
            accent = Color(0xFF7055DE), solid = Color(0xFF6247D6), ink = Color(0xFF5A3FC9),
            soft = Color(0xFFF0ECFF), line = Color(0xFFDED6FB),
        ),
    )

    private val darkAccents: Map<XfAccent, XfAccentColors> = mapOf(
        XfAccent.INDIGO to XfAccentColors(
            accent = Color(0xFF8B95FF), solid = Color(0xFF4F5DF0), ink = Color(0xFFA6AEFF),
            soft = Color(0x218B95FF), line = Color(0x478B95FF),
        ),
        XfAccent.TEAL to XfAccentColors(
            accent = Color(0xFF4FD1C5), solid = Color(0xFF0B7F73), ink = Color(0xFF7EE3D9),
            soft = Color(0x1F4FD1C5), line = Color(0x424FD1C5),
        ),
        XfAccent.AMBER to XfAccentColors(
            accent = Color(0xFFE8B05A), solid = Color(0xFF8F5600), ink = Color(0xFFF2C778),
            soft = Color(0x1FE8B05A), line = Color(0x42E8B05A),
        ),
        XfAccent.ROSE to XfAccentColors(
            accent = Color(0xFFFF8FA3), solid = Color(0xFFC2435C), ink = Color(0xFFFFAEBE),
            soft = Color(0x1FFF8FA3), line = Color(0x47FF8FA3),
        ),
        XfAccent.VIOLET to XfAccentColors(
            accent = Color(0xFFC5BCFF), solid = Color(0xFF7055DE), ink = Color(0xFFD8D0FF),
            soft = Color(0x24C5BCFF), line = Color(0x4CC5BCFF),
        ),
    )

    fun resolve(dark: Boolean, accent: XfAccent): XfColors {
        val base = if (dark) darkBase else lightBase
        val a = (if (dark) darkAccents else lightAccents).getValue(accent)
        return base.copy(
            accent = a.accent, accentSolid = a.solid, accentInk = a.ink,
            accentSoft = a.soft, accentLine = a.line,
        )
    }
}

internal val LocalXfColors = staticCompositionLocalOf { XfPalettes.lightBase }

internal object XfType {
    private val family = FontFamily.Default

    val display = TextStyle(fontFamily = family, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val h1 = TextStyle(fontFamily = family, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em)
    val h2 = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    val h3 = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val bodyStrong = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal)
    val meta = TextStyle(fontFamily = family, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val caps = TextStyle(fontFamily = family, fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val button = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val buttonSmall = TextStyle(fontFamily = family, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val nav = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}

/** Material3 slots remapped to the concept scale so existing screens inherit it. */
internal val XfTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = XfType.display,
        displayMedium = XfType.display.copy(fontSize = 26.sp, lineHeight = 32.sp),
        displaySmall = XfType.h1,
        headlineLarge = XfType.h1,
        headlineMedium = XfType.h1.copy(fontSize = 21.sp, lineHeight = 27.sp),
        headlineSmall = XfType.h2,
        titleLarge = XfType.h2,
        titleMedium = XfType.h3,
        titleSmall = XfType.title,
        bodyLarge = XfType.body,
        bodyMedium = XfType.small,
        bodySmall = XfType.meta,
        labelLarge = XfType.label.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
        labelMedium = XfType.meta.copy(fontWeight = FontWeight.Medium),
        labelSmall = XfType.meta.copy(fontSize = 11.sp, lineHeight = 15.sp),
    )
}

internal object XfDimens {
    val pagePadding = 20.dp
    val pagePaddingWide = 24.dp
    val cardPadding = 16.dp
    val cardPaddingCompact = 14.dp
    val sectionGap = 24.dp
    val itemGap = 12.dp
    val rowGap = 8.dp

    val heroRadius = 18.dp
    val cardRadius = 14.dp
    val panelRadius = 14.dp
    val dialogRadius = 16.dp
    val controlRadius = 10.dp
    val buttonRadius = 10.dp
    val smallRadius = 8.dp
    val chipRadius = 8.dp
    val pillRadius = 999.dp

    val touch = 48.dp
    val buttonHeight = 44.dp
    val buttonHeightSmall = 34.dp
    val inputHeight = 46.dp
    val iconButton = 40.dp
    val topBarHeight = 56.dp
    val bottomBarHeight = 80.dp
    val avatarSmall = 32.dp
    val avatarMedium = 40.dp
    val avatarLarge = 48.dp
    val tileSmall = 28.dp
    val tileMedium = 36.dp

    val borderWidth = 1.dp
    val iconSize = 20.dp
    val iconSizeSmall = 17.dp
    val iconSizeLarge = 24.dp
}

internal val XfShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Elevation presets matching the concept's three shadow steps (phone-scaled). */
internal object XfElevation {
    val none: Dp = 0.dp
    val s1: Dp = 0.dp
    val s2: Dp = 1.dp
    val s3: Dp = 8.dp
    val modal: Dp = 12.dp
}

/** Maps semantic colors onto the Material3 slots still used by older screens. */
internal fun XfColors.toMaterialScheme() = if (isDark) darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF0B0C10),
    primaryContainer = accentSoft,
    onPrimaryContainer = accentInk,
    inversePrimary = accentSolid,
    secondary = ink2,
    onSecondary = surface,
    secondaryContainer = surface2,
    onSecondaryContainer = ink,
    tertiary = blue,
    onTertiary = Color(0xFF0B0C10),
    tertiaryContainer = infoContainer,
    onTertiaryContainer = infoText,
    background = canvas,
    onBackground = ink,
    surface = surface,
    onSurface = ink,
    surfaceVariant = surface2,
    onSurfaceVariant = ink2,
    surfaceTint = accent,
    inverseSurface = ink,
    inverseOnSurface = canvas,
    error = red,
    onError = Color(0xFF0B0C10),
    errorContainer = dangerContainer,
    onErrorContainer = dangerText,
    outline = lineStrong,
    outlineVariant = line,
    scrim = overlay,
    surfaceBright = surface2,
    surfaceDim = canvas,
    surfaceContainer = surface2,
    surfaceContainerHigh = surface3,
    surfaceContainerHighest = surface3,
    surfaceContainerLow = canvas2,
    surfaceContainerLowest = canvas,
) else lightColorScheme(
    primary = accentSolid,
    onPrimary = Color.White,
    primaryContainer = accentSoft,
    onPrimaryContainer = accentInk,
    inversePrimary = accent,
    secondary = ink2,
    onSecondary = Color.White,
    secondaryContainer = surface2,
    onSecondaryContainer = ink,
    tertiary = blue,
    onTertiary = Color.White,
    tertiaryContainer = infoContainer,
    onTertiaryContainer = infoText,
    background = canvas,
    onBackground = ink,
    surface = surface,
    onSurface = ink,
    surfaceVariant = surface2,
    onSurfaceVariant = ink2,
    surfaceTint = accentSolid,
    inverseSurface = ink,
    inverseOnSurface = canvas,
    error = dangerText,
    onError = Color.White,
    errorContainer = dangerContainer,
    onErrorContainer = dangerText,
    outline = lineStrong,
    outlineVariant = line,
    scrim = overlay,
    surfaceBright = surface,
    surfaceDim = canvas2,
    surfaceContainer = surface2,
    surfaceContainerHigh = surface3,
    surfaceContainerHighest = surface3,
    surfaceContainerLow = canvas2,
    surfaceContainerLowest = surface,
)

@Composable
internal fun XfTheme(
    mode: XfThemeMode = XfAppearance.mode,
    accent: XfAccent = XfAppearance.accent,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        XfThemeMode.SYSTEM -> isSystemInDarkTheme()
        XfThemeMode.LIGHT -> false
        XfThemeMode.DARK -> true
    }
    val colors = remember(dark, accent) { XfPalettes.resolve(dark, accent) }
    val view = LocalView.current
    SideEffect { applyXfSystemBars(view, dark, colors) }
    CompositionLocalProvider(LocalXfColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = XfTypography,
            shapes = XfShapes,
            content = content,
        )
    }
}

private fun applyXfSystemBars(view: View, dark: Boolean, colors: XfColors) {
    val activity = view.context.findActivity() ?: return
    val window = activity.window
    @Suppress("DEPRECATION")
    window.statusBarColor = colors.canvas.toArgb()
    @Suppress("DEPRECATION")
    window.navigationBarColor = colors.canvas.toArgb()
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Convenience accessor used by all new components and migrated screens. */
internal val xf: XfColors
    @Composable get() = LocalXfColors.current
