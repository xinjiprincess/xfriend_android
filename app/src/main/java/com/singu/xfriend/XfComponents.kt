package com.singu.xfriend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 重塑概念稿 v1 的共享组件层。
 *
 * 新页面应优先使用这里的组件；它们只读取 [LocalXfColors]（即 `xf`）和 [XfDimens]，
 * 因此亮/暗主题与 5 组主色切换会自动生效。不要在页面里再复制颜色、圆角或按钮样式。
 */

internal enum class XfButtonKind { Primary, Secondary, Ghost, Danger }

internal enum class XfTone { Neutral, Accent, Success, Warning, Danger, Info }

@Composable
internal fun XfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = XfDimens.cardPadding,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = xf
    val shape = RoundedCornerShape(XfDimens.cardRadius)
    val interaction = if (onClick != null) {
        modifier.clip(shape).clickable(onClick = onClick)
    } else {
        modifier
    }
    Surface(
        modifier = interaction,
        shape = shape,
        color = colors.surface,
        contentColor = colors.ink,
        border = BorderStroke(XfDimens.borderWidth, colors.line),
        shadowElevation = if (elevated) XfElevation.s2 else XfElevation.s1,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Card whose content manages its own padding (search fields, media rows). */
@Composable
internal fun XfSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(XfDimens.cardRadius),
    color: Color? = null,
    border: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = xf
    val interaction = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier
    Surface(
        modifier = interaction,
        shape = shape,
        color = color ?: colors.surface,
        contentColor = colors.ink,
        border = if (border) BorderStroke(XfDimens.borderWidth, colors.line) else null,
    ) { content() }
}

@Composable
internal fun XfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = xf
    Row(
        modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = XfType.h3, color = colors.ink)
            if (subtitle != null) Text(subtitle, style = XfType.meta, color = colors.ink3)
        }
        if (action != null && onAction != null) XfLink(action, onClick = onAction)
    }
}

@Composable
internal fun XfSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
        style = XfType.caps,
        color = xf.ink3,
    )
}

@Composable
internal fun XfPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = xf
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = XfType.h1, color = colors.ink)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = XfType.small, color = colors.ink2)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
internal fun XfTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    brandIconRes: Int? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = xf
    Row(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(XfDimens.topBarHeight).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            XfIconButton(XfIcons.ChevRight, "返回", onBack, rotation = 180f)
            Spacer(Modifier.width(4.dp))
        } else if (brandIconRes != null) {
            XfBrandTile(brandIconRes, 30.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = XfType.title.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = XfType.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
internal fun XfBrandTile(iconRes: Int, size: Dp = 30.dp, radius: Dp = 9.dp) {
    val colors = xf
    Surface(
        shape = RoundedCornerShape(radius),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = Modifier.size(size),
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = ProductBrand.name,
            modifier = Modifier.fillMaxSize().padding(size * .16f),
        )
    }
}

/** Concept's star mark for in-app brand moments (login, hero, empty illustration). */
@Composable
internal fun XfStarMark(modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color? = null) {
    val colors = xf
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * .28f))
            .background(colors.accentSoft)
            .border(1.dp, colors.accentLine, RoundedCornerShape(size * .28f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(XfIcons.Brand, contentDescription = null, modifier = Modifier.size(size * .56f), tint = tint ?: colors.accent)
    }
}

@Composable
internal fun XfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    kind: XfButtonKind = XfButtonKind.Primary,
    small: Boolean = false,
    loading: Boolean = false,
) {
    val colors = xf
    val height = if (small) XfDimens.buttonHeightSmall else XfDimens.buttonHeight
    val radius = RoundedCornerShape(if (small) XfDimens.smallRadius else XfDimens.buttonRadius)
    val background = when (kind) {
        XfButtonKind.Primary -> colors.accentSolid
        XfButtonKind.Secondary -> colors.surface
        XfButtonKind.Ghost -> Color.Transparent
        XfButtonKind.Danger -> colors.dangerSoft
    }
    val contentColor = when (kind) {
        XfButtonKind.Primary -> Color.White
        XfButtonKind.Secondary -> colors.ink
        XfButtonKind.Ghost -> colors.ink2
        XfButtonKind.Danger -> colors.red
    }
    val border = when (kind) {
        XfButtonKind.Secondary -> BorderStroke(1.dp, colors.line)
        XfButtonKind.Danger -> BorderStroke(1.dp, colors.red.copy(alpha = .28f))
        else -> null
    }
    Surface(
        modifier = modifier.heightIn(min = height).clip(radius)
            .then(if (enabled && !loading) Modifier.clickable(onClick = onClick) else Modifier),
        shape = radius,
        color = if (enabled) background else background.copy(alpha = .45f),
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = .5f),
        border = border,
    ) {
        Row(
            Modifier.heightIn(min = height).padding(horizontal = if (small) 12.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = contentColor)
                Spacer(Modifier.width(7.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(if (small) 16.dp else 18.dp), tint = contentColor)
                Spacer(Modifier.width(7.dp))
            }
            Text(text, style = if (small) XfType.buttonSmall else XfType.button, maxLines = 1)
        }
    }
}

@Composable
internal fun XfIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Dp = XfDimens.iconButton,
    rotation: Float = 0f,
    tint: Color? = null,
) {
    val colors = xf
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.minimumInteractiveComponentSize().size(size).clip(shape)
            .then(if (active) Modifier.background(colors.accentSoft) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(if (size >= 44.dp) 22.dp else 19.dp).rotate(rotation),
            tint = when {
                !enabled -> colors.ink4
                active -> colors.accentInk
                tint != null -> tint
                else -> colors.ink2
            },
        )
    }
}

@Composable
internal fun XfLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = xf
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, style = XfType.label, color = colors.ink3)
        Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(13.dp), tint = colors.ink4)
    }
}

@Composable
internal fun XfDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier.background(color.copy(alpha = .14f), CircleShape).padding(size * .42f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

@Composable
internal fun XfStatusChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: XfTone = XfTone.Neutral,
    icon: ImageVector? = null,
) {
    val colors = xf
    val (foreground, background) = when (tone) {
        XfTone.Success -> colors.successText to colors.successContainer
        XfTone.Warning -> colors.warningText to colors.warningContainer
        XfTone.Danger -> colors.dangerText to colors.dangerContainer
        XfTone.Info -> colors.infoText to colors.infoContainer
        XfTone.Accent -> colors.accentInk to colors.accentSoft
        XfTone.Neutral -> colors.ink2 to colors.surface2
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(XfDimens.smallRadius),
        color = background,
        border = if (tone == XfTone.Neutral) BorderStroke(1.dp, colors.line) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = foreground)
            else if (tone != XfTone.Neutral) XfDot(foreground)
            Text(label, style = XfType.meta.copy(fontWeight = FontWeight.Medium), color = foreground)
        }
    }
}

@Composable
internal fun XfChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    tone: XfTone? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = xf
    val accentTone = tone == XfTone.Accent || tone == XfTone.Info
    val background = when {
        selected || accentTone -> colors.accentSoft
        tone == XfTone.Success -> colors.successContainer
        tone == XfTone.Warning -> colors.warningContainer
        tone == XfTone.Danger -> colors.dangerContainer
        else -> colors.surface2
    }
    val contentColor = when {
        selected || accentTone -> colors.accentInk
        tone == XfTone.Success -> colors.successText
        tone == XfTone.Warning -> colors.warningText
        tone == XfTone.Danger -> colors.dangerText
        else -> colors.ink2
    }
    val shape = RoundedCornerShape(XfDimens.chipRadius)
    Surface(
        modifier = modifier.clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = background,
        border = if (selected || accentTone) BorderStroke(1.dp, colors.accentLine) else BorderStroke(1.dp, colors.line),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = contentColor)
            Text(text, style = XfType.meta.copy(fontWeight = FontWeight.Medium), color = contentColor)
        }
    }
}

@Composable
internal fun XfAvatar(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    size: Dp = XfDimens.avatarMedium,
    round: Boolean = false,
    tone: XfTone = XfTone.Accent,
) {
    val colors = xf
    val background = when (tone) {
        XfTone.Accent -> colors.accentSoft
        XfTone.Neutral -> colors.surface2
        else -> colors.surface3
    }
    val foreground = when (tone) {
        XfTone.Accent -> colors.accentInk
        else -> colors.ink2
    }
    val shape = if (round) CircleShape else RoundedCornerShape(size * .3f)
    Box(
        modifier.size(size).clip(shape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * .48f), tint = foreground)
        } else {
            Text(
                text.orEmpty().take(2),
                style = XfType.label.copy(fontWeight = FontWeight.SemiBold),
                color = foreground,
            )
        }
    }
}

@Composable
internal fun XfIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tone: XfTone = XfTone.Accent,
) {
    val colors = xf
    val background = when (tone) {
        XfTone.Accent -> colors.accentSoft
        XfTone.Success -> colors.successSoft
        XfTone.Warning -> colors.warningSoft
        XfTone.Danger -> colors.dangerSoft
        XfTone.Info -> colors.infoSoft
        XfTone.Neutral -> colors.surface2
    }
    val foreground = when (tone) {
        XfTone.Accent -> colors.accentInk
        XfTone.Success -> colors.successText
        XfTone.Warning -> colors.warningText
        XfTone.Danger -> colors.dangerText
        XfTone.Info -> colors.infoText
        XfTone.Neutral -> colors.ink3
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * .3f)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * .5f), tint = foreground)
    }
}

@Composable
internal fun XfListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    minHeight: Dp = 56.dp,
) {
    val colors = xf
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = minHeight)
                .padding(horizontal = 2.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = XfType.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = XfType.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (meta != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, style = XfType.meta, color = colors.ink4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
        if (showDivider) Hr()
    }
}

@Composable
internal fun Hr(modifier: Modifier = Modifier, color: Color? = null) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color ?: xf.line))
}

@Composable
internal fun XfSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    trailing: (@Composable () -> Unit)? = null,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val colors = xf
    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(XfDimens.controlRadius),
            color = colors.surface2,
            border = BorderStroke(1.dp, colors.line),
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                .alpha(if (enabled) 1f else .55f),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(XfIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink4)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    textStyle = XfType.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text(placeholder, style = XfType.body, color = colors.ink4)
                            inner()
                        }
                    },
                )
                if (value.isNotEmpty()) {
                    XfIconButton(XfIcons.X, "清除", { onValueChange("") }, size = 28.dp)
                }
                trailing?.invoke()
            }
        }
        if (supporting != null) Text(supporting, style = XfType.meta, color = colors.ink3, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

@Composable
internal fun XfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = xf
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = XfType.label, color = colors.ink2, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        }
        val shape = RoundedCornerShape(XfDimens.controlRadius)
        Surface(
            shape = shape,
            color = colors.surface,
            border = BorderStroke(1.dp, if (isError) colors.red.copy(alpha = .6f) else colors.line),
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else .55f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    textStyle = XfType.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = singleLine,
                    minLines = minLines,
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    decorationBox = { inner ->
                        Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                            if (value.isEmpty()) Text(placeholder, style = XfType.body, color = colors.ink4)
                            inner()
                        }
                    },
                )
                trailing?.invoke()
            }
        }
        if (supporting != null) {
            Text(
                supporting,
                style = XfType.meta,
                color = if (isError) colors.red else colors.ink3,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

@Composable
internal fun XfSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = xf
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(colors.surface2).border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(9.dp))
                    .then(if (selected) Modifier.background(colors.surface).border(1.dp, colors.line, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = XfType.label,
                    color = if (selected) colors.ink else colors.ink3,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun XfProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val colors = xf
    val shape = RoundedCornerShape(999.dp)
    Box(modifier.fillMaxWidth().height(4.dp).clip(shape).background(colors.surface3)) {
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colors.accent,
                trackColor = Color.Transparent,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        } else {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(shape)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
internal fun XfBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: XfTone = XfTone.Neutral,
    icon: ImageVector? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = xf
    val (foreground, background) = when (tone) {
        XfTone.Success -> colors.successText to colors.successContainer
        XfTone.Warning -> colors.warningText to colors.warningContainer
        XfTone.Danger -> colors.dangerText to colors.dangerContainer
        XfTone.Info -> colors.infoText to colors.infoContainer
        XfTone.Accent -> colors.accentInk to colors.accentSoft
        XfTone.Neutral -> colors.ink2 to colors.surface2
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(XfDimens.controlRadius),
        color = background,
        border = BorderStroke(1.dp, foreground.copy(alpha = .18f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = foreground)
            Text(text, Modifier.weight(1f), style = XfType.small, color = foreground)
            if (action != null && onAction != null) {
                Text(
                    action,
                    style = XfType.label,
                    color = foreground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun XfEmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = xf
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.ink3)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = XfType.h3, color = colors.ink)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = XfType.small, color = colors.ink3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            XfButton(action, onAction, small = true)
        }
    }
}

@Composable
internal fun XfDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = "取消",
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = xf
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(XfDimens.dialogRadius),
        title = { Text(title, style = XfType.h2) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
        confirmButton = {
            XfButton(
                confirmLabel, onConfirm,
                kind = if (danger) XfButtonKind.Danger else XfButtonKind.Primary,
                enabled = confirmEnabled, small = true,
            )
        },
        dismissButton = {
            if (dismissLabel != null) XfButton(dismissLabel, onDismiss, kind = XfButtonKind.Ghost, small = true)
        },
    )
}

@Composable
internal fun XfKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val colors = xf
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = XfType.small, color = colors.ink2)
        Text(value, style = XfType.small, color = valueColor ?: colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun XfSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = xf
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.heightIn(min = XfDimens.touch),
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.accentSolid,
            checkedBorderColor = colors.accentSolid,
            uncheckedThumbColor = colors.ink3,
            uncheckedTrackColor = colors.surface3,
            uncheckedBorderColor = colors.lineStrong,
            disabledCheckedThumbColor = colors.ink4,
            disabledUncheckedThumbColor = colors.ink4,
        ),
    )
}

@Composable
internal fun XfLabeledText(
    label: String,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = XfType.body,
) {
    val colors = xf
    Column(modifier) {
        Text(label, style = XfType.caps, color = colors.ink3)
        Spacer(Modifier.height(3.dp))
        Text(text, style = style, color = colors.ink)
    }
}
