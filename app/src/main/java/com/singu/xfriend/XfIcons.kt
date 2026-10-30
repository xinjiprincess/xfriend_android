package com.singu.xfriend

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Singu Xfriend 重塑概念稿 v1 的线性图标语言（24 视窗、1.75 描边、圆头圆角）。
 *
 * 由 scripts/generate_xf_icons.py 从概念稿内联 SVG 自动生成，请勿手改；
 * 需要新图标时先改概念稿再重新生成。图标统一使用 Icon(tint = ...) 着色。
 */
internal object XfIcons {
    val ArrowUp: ImageVector by lazy { xfVector("ArrowUp", listOf(
        XfPath("M12 19V5"),
        XfPath("m5.5 11.5 6.5-6.5 6.5 6.5"),
    )) }
    val At: ImageVector by lazy { xfVector("At", listOf(
        XfPath("M8,12A4,4 0 1 0 16,12A4,4 0 1 0 8,12Z"),
        XfPath("M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-3.9 7.9"),
    )) }
    val Bell: ImageVector by lazy { xfVector("Bell", listOf(
        XfPath("M6 8.6a6 6 0 0 1 12 0c0 6 2.4 7.4 2.4 7.4H3.6S6 14.6 6 8.6"),
        XfPath("M10.2 20.4a2 2 0 0 0 3.6 0"),
    )) }
    val Blocks: ImageVector by lazy { xfVector("Blocks", listOf(
        XfPath("M5,3H8.5A2,2 0 0 1 10.5,5V8.5A2,2 0 0 1 8.5,10.5H5A2,2 0 0 1 3,8.5V5A2,2 0 0 1 5,3Z"),
        XfPath("M15.5,3H19A2,2 0 0 1 21,5V8.5A2,2 0 0 1 19,10.5H15.5A2,2 0 0 1 13.5,8.5V5A2,2 0 0 1 15.5,3Z"),
        XfPath("M5,13.5H8.5A2,2 0 0 1 10.5,15.5V19A2,2 0 0 1 8.5,21H5A2,2 0 0 1 3,19V15.5A2,2 0 0 1 5,13.5Z"),
        XfPath("M17.25 13.5v7.5M13.5 17.25h7.5"),
    )) }
    val Book: ImageVector by lazy { xfVector("Book", listOf(
        XfPath("M2.8 4.2h5.4A3.8 3.8 0 0 1 12 8v12a3.2 3.2 0 0 0-3.2-3.2H2.8Z"),
        XfPath("M21.2 4.2h-5.4A3.8 3.8 0 0 0 12 8v12a3.2 3.2 0 0 1 3.2-3.2h6Z"),
    )) }
    val Brand: ImageVector by lazy { xfVector("Brand", listOf(
        XfPath("M12 2.4c.62 4.94 2.62 6.94 7.56 7.56-4.94.62-6.94 2.62-7.56 7.56-.62-4.94-2.62-6.94-7.56-7.56C9.38 9.34 11.38 7.34 12 2.4Z", fill = true),
        XfPath("M18.7 15.1c.3 2.36 1.25 3.31 3.61 3.61-2.36.3-3.31 1.25-3.61 3.61-.3-2.36-1.25-3.31-3.61-3.61 2.36-.3 3.31-1.25 3.61-3.61Z", fill = true, alpha = 0.72f),
    )) }
    val Calendar: ImageVector by lazy { xfVector("Calendar", listOf(
        XfPath("M6.5,5H17.5A3,3 0 0 1 20.5,8V18A3,3 0 0 1 17.5,21H6.5A3,3 0 0 1 3.5,18V8A3,3 0 0 1 6.5,5Z"),
        XfPath("M3.5 10h17M8 3v4M16 3v4"),
    )) }
    val Chart: ImageVector by lazy { xfVector("Chart", listOf(
        XfPath("M3.5 20.5h17"),
        XfPath("M6.7,10H8.1A1.2,1.2 0 0 1 9.3,11.2V16.8A1.2,1.2 0 0 1 8.1,18H6.7A1.2,1.2 0 0 1 5.5,16.8V11.2A1.2,1.2 0 0 1 6.7,10Z"),
        XfPath("M11.8,5H13.2A1.2,1.2 0 0 1 14.4,6.2V16.8A1.2,1.2 0 0 1 13.2,18H11.8A1.2,1.2 0 0 1 10.6,16.8V6.2A1.2,1.2 0 0 1 11.8,5Z"),
        XfPath("M16.9,13H18.3A1.2,1.2 0 0 1 19.5,14.2V16.8A1.2,1.2 0 0 1 18.3,18H16.9A1.2,1.2 0 0 1 15.7,16.8V14.2A1.2,1.2 0 0 1 16.9,13Z"),
    )) }
    val Chat: ImageVector by lazy { xfVector("Chat", listOf(
        XfPath("M20.5 14.2a2.3 2.3 0 0 1-2.3 2.3H8.4L4 20.5V5.8a2.3 2.3 0 0 1 2.3-2.3H18.2a2.3 2.3 0 0 1 2.3 2.3Z"),
    )) }
    val Check: ImageVector by lazy { xfVector("Check", listOf(
        XfPath("m5 12.5 4.7 4.7L19.5 7"),
    )) }
    val CheckCircle: ImageVector by lazy { xfVector("CheckCircle", listOf(
        XfPath("M3.2,12A8.8,8.8 0 1 0 20.8,12A8.8,8.8 0 1 0 3.2,12Z"),
        XfPath("m8.2 12.2 2.7 2.7 5-5.4"),
    )) }
    val ChevDown: ImageVector by lazy { xfVector("ChevDown", listOf(
        XfPath("m6 9.5 6 6 6-6"),
    )) }
    val ChevRight: ImageVector by lazy { xfVector("ChevRight", listOf(
        XfPath("m9.5 6 6 6-6 6"),
    )) }
    val Clip: ImageVector by lazy { xfVector("Clip", listOf(
        XfPath("m20.9 11.4-8.6 8.6a5.5 5.5 0 0 1-7.8-7.8l9-9a3.7 3.7 0 0 1 5.2 5.2l-8.9 8.9a1.9 1.9 0 0 1-2.7-2.7l8.2-8.2"),
    )) }
    val Clock: ImageVector by lazy { xfVector("Clock", listOf(
        XfPath("M3.2,12A8.8,8.8 0 1 0 20.8,12A8.8,8.8 0 1 0 3.2,12Z"),
        XfPath("M12 7v5.4l3.4 2"),
    )) }
    val Code: ImageVector by lazy { xfVector("Code", listOf(
        XfPath("m16 18 5.5-6L16 6M8 6l-5.5 6L8 18"),
    )) }
    val Command: ImageVector by lazy { xfVector("Command", listOf(
        XfPath("M9 6.5a2.5 2.5 0 1 0-2.5 2.5H17a2.5 2.5 0 1 0-2.5-2.5V17a2.5 2.5 0 1 0 2.5-2.5H6.5A2.5 2.5 0 1 0 9 17Z"),
    )) }
    val Copy: ImageVector by lazy { xfVector("Copy", listOf(
        XfPath("M11,9H18A2,2 0 0 1 20,11V18A2,2 0 0 1 18,20H11A2,2 0 0 1 9,18V11A2,2 0 0 1 11,9Z"),
        XfPath("M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"),
    )) }
    val Cpu: ImageVector by lazy { xfVector("Cpu", listOf(
        XfPath("M9.2,6.8H14.8A2.4,2.4 0 0 1 17.2,9.2V14.8A2.4,2.4 0 0 1 14.8,17.2H9.2A2.4,2.4 0 0 1 6.8,14.8V9.2A2.4,2.4 0 0 1 9.2,6.8Z"),
        XfPath("M9.5 2.5v3.2M14.5 2.5v3.2M9.5 18.3v3.2M14.5 18.3v3.2M2.5 9.5h3.2M2.5 14.5h3.2M18.3 9.5h3.2M18.3 14.5h3.2"),
    )) }
    val Database: ImageVector by lazy { xfVector("Database", listOf(
        XfPath("M4.4,5.6A7.6,2.9 0 1 0 19.6,5.6A7.6,2.9 0 1 0 4.4,5.6Z"),
        XfPath("M4.4 5.6v12.8c0 1.6 3.4 2.9 7.6 2.9s7.6-1.3 7.6-2.9V5.6"),
        XfPath("M4.4 12c0 1.6 3.4 2.9 7.6 2.9s7.6-1.3 7.6-2.9"),
    )) }
    val Edit: ImageVector by lazy { xfVector("Edit", listOf(
        XfPath("M12 20.5h9"),
        XfPath("M16.6 3.9a2 2 0 0 1 2.8 2.8L7.6 18.5l-3.9 1 1-3.9Z"),
    )) }
    val Export: ImageVector by lazy { xfVector("Export", listOf(
        XfPath("M12 3.5v11"),
        XfPath("m7.5 10 4.5 4.5L16.5 10"),
        XfPath("M4 17.5v1.2a2.3 2.3 0 0 0 2.3 2.3h11.4a2.3 2.3 0 0 0 2.3-2.3v-1.2"),
    )) }
    val File: ImageVector by lazy { xfVector("File", listOf(
        XfPath("M14 2.8H7.2a2.2 2.2 0 0 0-2.2 2.2v14a2.2 2.2 0 0 0 2.2 2.2h9.6a2.2 2.2 0 0 0 2.2-2.2V7.8Z"),
        XfPath("M14 2.8v5h5"),
        XfPath("M8.8 13h6.4M8.8 16.6h6.4"),
    )) }
    val Filter: ImageVector by lazy { xfVector("Filter", listOf(
        XfPath("M3.5 5.5h17l-6.6 7.6v5.6l-3.8 2v-7.6Z"),
    )) }
    val Fingerprint: ImageVector by lazy { xfVector("Fingerprint", listOf(
        XfPath("M7.5 8.5a5 5 0 0 1 9 0"),
        XfPath("M5.5 12a7 7 0 0 1 13 0"),
        XfPath("M12 11a2.5 2.5 0 0 1 2.5 2.5c0 2-.5 3.5-1 4.5"),
        XfPath("M9 15c0 2 .6 3.6 1.5 4.5"),
        XfPath("M9.5 8.8a3 3 0 0 1 4.8 0"),
    )) }
    val Folder: ImageVector by lazy { xfVector("Folder", listOf(
        XfPath("M3.2 7.2A2.2 2.2 0 0 1 5.4 5h4l2.2 2.2h7a2.2 2.2 0 0 1 2.2 2.2v8.4a2.2 2.2 0 0 1-2.2 2.2H5.4a2.2 2.2 0 0 1-2.2-2.2Z"),
    )) }
    val Globe: ImageVector by lazy { xfVector("Globe", listOf(
        XfPath("M3.2,12A8.8,8.8 0 1 0 20.8,12A8.8,8.8 0 1 0 3.2,12Z"),
        XfPath("M3.2 12h17.6"),
        XfPath("M12 3.2c2.4 2.4 3.6 5.4 3.6 8.8s-1.2 6.4-3.6 8.8c-2.4-2.4-3.6-5.4-3.6-8.8S9.6 5.6 12 3.2Z"),
    )) }
    val Grid: ImageVector by lazy { xfVector("Grid", listOf(
        XfPath("M5.5,3.5H8.5A2,2 0 0 1 10.5,5.5V8.5A2,2 0 0 1 8.5,10.5H5.5A2,2 0 0 1 3.5,8.5V5.5A2,2 0 0 1 5.5,3.5Z"),
        XfPath("M15.5,3.5H18.5A2,2 0 0 1 20.5,5.5V8.5A2,2 0 0 1 18.5,10.5H15.5A2,2 0 0 1 13.5,8.5V5.5A2,2 0 0 1 15.5,3.5Z"),
        XfPath("M5.5,13.5H8.5A2,2 0 0 1 10.5,15.5V18.5A2,2 0 0 1 8.5,20.5H5.5A2,2 0 0 1 3.5,18.5V15.5A2,2 0 0 1 5.5,13.5Z"),
        XfPath("M15.5,13.5H18.5A2,2 0 0 1 20.5,15.5V18.5A2,2 0 0 1 18.5,20.5H15.5A2,2 0 0 1 13.5,18.5V15.5A2,2 0 0 1 15.5,13.5Z"),
    )) }
    val Help: ImageVector by lazy { xfVector("Help", listOf(
        XfPath("M3.2,12A8.8,8.8 0 1 0 20.8,12A8.8,8.8 0 1 0 3.2,12Z"),
        XfPath("M9.4 9.2a2.7 2.7 0 0 1 5.2 1c0 1.8-2.6 1.9-2.6 3.6"),
        XfPath("M12 17.1h.01"),
    )) }
    val Home: ImageVector by lazy { xfVector("Home", listOf(
        XfPath("M4 10.4 12 3.2l8 7.2"),
        XfPath("M6 9.4V19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V9.4"),
    )) }
    val Inbox: ImageVector by lazy { xfVector("Inbox", listOf(
        XfPath("M3.5 13.5h4.2l1.5 2.4h5.6l1.5-2.4h4.2"),
        XfPath("M5.6 4.5h12.8l2.1 9v4.2a2.2 2.2 0 0 1-2.2 2.2H5.7a2.2 2.2 0 0 1-2.2-2.2v-4.2Z"),
    )) }
    val Laptop: ImageVector by lazy { xfVector("Laptop", listOf(
        XfPath("M6,5H18A2,2 0 0 1 20,7V14A2,2 0 0 1 18,16H6A2,2 0 0 1 4,14V7A2,2 0 0 1 6,5Z"),
        XfPath("M2 19h20"),
    )) }
    val Layers: ImageVector by lazy { xfVector("Layers", listOf(
        XfPath("m12 2.8 9 4.6-9 4.6-9-4.6Z"),
        XfPath("m3 12.2 9 4.6 9-4.6"),
        XfPath("m3 16.8 9 4.6 9-4.6"),
    )) }
    val Meeting: ImageVector by lazy { xfVector("Meeting", listOf(
        XfPath("M3 4h18"),
        XfPath("M20 4v9.5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4"),
        XfPath("m8.5 20.5 3.5-3.5 3.5 3.5"),
        XfPath("M10 10.5v-2"),
        XfPath("M12.6,9.5A1.4,1.4 0 1 0 15.4,9.5A1.4,1.4 0 1 0 12.6,9.5Z"),
    )) }
    val Mic: ImageVector by lazy { xfVector("Mic", listOf(
        XfPath("M12,2.5H12A3,3 0 0 1 15,5.5V10.5A3,3 0 0 1 12,13.5H12A3,3 0 0 1 9,10.5V5.5A3,3 0 0 1 12,2.5Z"),
        XfPath("M5.2 10.5v1.2a6.8 6.8 0 0 0 13.6 0v-1.2"),
        XfPath("M12 18.5v3"),
    )) }
    val Monitor: ImageVector by lazy { xfVector("Monitor", listOf(
        XfPath("M4.5,4H19.5A2,2 0 0 1 21.5,6V15A2,2 0 0 1 19.5,17H4.5A2,2 0 0 1 2.5,15V6A2,2 0 0 1 4.5,4Z"),
        XfPath("M8 21h8M12 17v4"),
    )) }
    val Moon: ImageVector by lazy { xfVector("Moon", listOf(
        XfPath("M20.8 12.9A8.8 8.8 0 1 1 11.1 3.2a6.9 6.9 0 0 0 9.7 9.7Z"),
    )) }
    val More: ImageVector by lazy { xfVector("More", listOf(
        XfPath("M4.4,12A1.1,1.1 0 1 0 6.6,12A1.1,1.1 0 1 0 4.4,12Z", fill = true),
        XfPath("M10.9,12A1.1,1.1 0 1 0 13.1,12A1.1,1.1 0 1 0 10.9,12Z", fill = true),
        XfPath("M17.4,12A1.1,1.1 0 1 0 19.6,12A1.1,1.1 0 1 0 17.4,12Z", fill = true),
    )) }
    val Palette: ImageVector by lazy { xfVector("Palette", listOf(
        XfPath("M12 3.2a8.8 8.8 0 1 0 0 17.6c1.5 0 2.3-.9 2.3-2 0-.9-.5-1.4-.5-2.2 0-1.1.9-1.9 2.1-1.9h1.5c2.1 0 3.4-1.4 3.4-3.4A8.9 8.9 0 0 0 12 3.2Z"),
        XfPath("M6.5,11.4A1.1,1.1 0 1 0 8.7,11.4A1.1,1.1 0 1 0 6.5,11.4Z", fill = true),
        XfPath("M9.2,7.6A1.1,1.1 0 1 0 11.4,7.6A1.1,1.1 0 1 0 9.2,7.6Z", fill = true),
        XfPath("M13.7,7.8A1.1,1.1 0 1 0 15.9,7.8A1.1,1.1 0 1 0 13.7,7.8Z", fill = true),
    )) }
    val Panel: ImageVector by lazy { xfVector("Panel", listOf(
        XfPath("M6.5,3.5H17.5A3.5,3.5 0 0 1 21,7V17A3.5,3.5 0 0 1 17.5,20.5H6.5A3.5,3.5 0 0 1 3,17V7A3.5,3.5 0 0 1 6.5,3.5Z"),
        XfPath("M9.5 3.5v17"),
    )) }
    val Pause: ImageVector by lazy { xfVector("Pause", listOf(
        XfPath("M8.3,4.5H9A1.3,1.3 0 0 1 10.3,5.8V18.2A1.3,1.3 0 0 1 9,19.5H8.3A1.3,1.3 0 0 1 7,18.2V5.8A1.3,1.3 0 0 1 8.3,4.5Z", fill = true),
        XfPath("M15,4.5H15.7A1.3,1.3 0 0 1 17,5.8V18.2A1.3,1.3 0 0 1 15.7,19.5H15A1.3,1.3 0 0 1 13.7,18.2V5.8A1.3,1.3 0 0 1 15,4.5Z", fill = true),
    )) }
    val Pin: ImageVector by lazy { xfVector("Pin", listOf(
        XfPath("M12 17v5"),
        XfPath("M9 3h6l-1 6 3 3H7l3-3-1-6Z"),
    )) }
    val Play: ImageVector by lazy { xfVector("Play", listOf(
        XfPath("M7 4.6v14.8L19.5 12Z", fill = true),
    )) }
    val Plug: ImageVector by lazy { xfVector("Plug", listOf(
        XfPath("M12 21.5v-5"),
        XfPath("M9 7.5V3"),
        XfPath("M15 7.5V3"),
        XfPath("M17.5 7.5v4.7a4.3 4.3 0 0 1-4.3 4.3h-2.4a4.3 4.3 0 0 1-4.3-4.3V7.5Z"),
    )) }
    val Plus: ImageVector by lazy { xfVector("Plus", listOf(
        XfPath("M12 5v14M5 12h14"),
    )) }
    val Refresh: ImageVector by lazy { xfVector("Refresh", listOf(
        XfPath("M20 11a8 8 0 1 0-2.3 5.7"),
        XfPath("M20 4v7h-7"),
    )) }
    val Search: ImageVector by lazy { xfVector("Search", listOf(
        XfPath("M4.2,11A6.8,6.8 0 1 0 17.8,11A6.8,6.8 0 1 0 4.2,11Z"),
        XfPath("m20 20-4-4"),
    )) }
    val Settings: ImageVector by lazy { xfVector("Settings", listOf(
        XfPath("M4 7h6M14 7h6M4 12h11M19 12h1M4 17h3M11 17h9"),
        XfPath("M10,7A2,2 0 1 0 14,7A2,2 0 1 0 10,7Z"),
        XfPath("M15,12A2,2 0 1 0 19,12A2,2 0 1 0 15,12Z"),
        XfPath("M7,17A2,2 0 1 0 11,17A2,2 0 1 0 7,17Z"),
    )) }
    val Shield: ImageVector by lazy { xfVector("Shield", listOf(
        XfPath("M12 21.5s7.5-3.6 7.5-9.4V5.5L12 2.8 4.5 5.5v6.6c0 5.8 7.5 9.4 7.5 9.4Z"),
        XfPath("m9 12 2.1 2.1 4-4.2"),
    )) }
    val Sparkles: ImageVector by lazy { xfVector("Sparkles", listOf(
        XfPath("M12 3.2 13.8 9a2 2 0 0 0 1.2 1.2l5.8 1.8-5.8 1.8A2 2 0 0 0 13.8 15L12 20.8 10.2 15A2 2 0 0 0 9 13.8L3.2 12 9 10.2A2 2 0 0 0 10.2 9Z"),
        XfPath("M5 3.6v4M3 5.6h4M19 16.6v4M17 18.6h4"),
    )) }
    val Stop: ImageVector by lazy { xfVector("Stop", listOf(
        XfPath("M8.5,5.5H15.5A3,3 0 0 1 18.5,8.5V15.5A3,3 0 0 1 15.5,18.5H8.5A3,3 0 0 1 5.5,15.5V8.5A3,3 0 0 1 8.5,5.5Z", fill = true),
    )) }
    val Sun: ImageVector by lazy { xfVector("Sun", listOf(
        XfPath("M8,12A4,4 0 1 0 16,12A4,4 0 1 0 8,12Z"),
        XfPath("M12 2.5v2M12 19.5v2M4.6 4.6 6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"),
    )) }
    val Tasks: ImageVector by lazy { xfVector("Tasks", listOf(
        XfPath("M7.5,3.5H16.5A4,4 0 0 1 20.5,7.5V16.5A4,4 0 0 1 16.5,20.5H7.5A4,4 0 0 1 3.5,16.5V7.5A4,4 0 0 1 7.5,3.5Z"),
        XfPath("m8.4 12.1 2.3 2.3 4.9-5"),
    )) }
    val ThumbDown: ImageVector by lazy { xfVector("ThumbDown", listOf(
        XfPath("M17 14V3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-3Z"),
        XfPath("M17 14l-4 7a2 2 0 0 1-2-2v-5H6a2 2 0 0 1-2-2.3l1.5-7A2 2 0 0 1 7.5 3H17"),
    )) }
    val ThumbUp: ImageVector by lazy { xfVector("ThumbUp", listOf(
        XfPath("M7 10v11H4a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1h3Z"),
        XfPath("M7 10l4-7a2 2 0 0 1 2 2v5h5a2 2 0 0 1 2 2.3l-1.5 7A2 2 0 0 1 16.5 21H7"),
    )) }
    val Trending: ImageVector by lazy { xfVector("Trending", listOf(
        XfPath("m3.5 16.5 5.6-5.6 4 4 7.4-7.4"),
        XfPath("M15.2 7.5h5.3v5.3"),
    )) }
    val User: ImageVector by lazy { xfVector("User", listOf(
        XfPath("M8,8A4,4 0 1 0 16,8A4,4 0 1 0 8,8Z"),
        XfPath("M4.5 21a7.5 7.5 0 0 1 15 0"),
    )) }
    val UserPlus: ImageVector by lazy { xfVector("UserPlus", listOf(
        XfPath("M14.5 20.5v-1.7a3.7 3.7 0 0 0-3.7-3.7H6.3a3.7 3.7 0 0 0-3.7 3.7v1.7"),
        XfPath("M5,7A3.6,3.6 0 1 0 12.2,7A3.6,3.6 0 1 0 5,7Z"),
        XfPath("M18.5 7v5M16 9.5h5"),
    )) }
    val Users: ImageVector by lazy { xfVector("Users", listOf(
        XfPath("M15.5 21v-1.8a3.7 3.7 0 0 0-3.7-3.7H6.2a3.7 3.7 0 0 0-3.7 3.7V21"),
        XfPath("M5.4,7A3.6,3.6 0 1 0 12.6,7A3.6,3.6 0 1 0 5.4,7Z"),
        XfPath("M21.5 21v-1.8a3.7 3.7 0 0 0-2.8-3.6"),
        XfPath("M15.6 3.5a3.6 3.6 0 0 1 0 7"),
    )) }
    val X: ImageVector by lazy { xfVector("X", listOf(
        XfPath("M18 6 6 18M6 6l12 12"),
    )) }
}

private data class XfPath(val data: String, val fill: Boolean = false, val alpha: Float = 1f)

private fun xfVector(name: String, paths: List<XfPath>): ImageVector {
    val builder = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    paths.forEach { path ->
        val nodes = PathParser().parsePathString(path.data).toNodes()
        if (path.fill) {
            builder.addPath(nodes, fill = SolidColor(Color.Black), fillAlpha = path.alpha, stroke = null)
        } else {
            builder.addPath(
                nodes,
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return builder.build()
}
