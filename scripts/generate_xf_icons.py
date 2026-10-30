"""Generate Compose XfIcons.kt from the Singu Xfriend redesign concept's inline SVG symbols.

Reads D:/octop/docs/design/redesign-concept.html, converts every <symbol id="i-...">
into an ImageVector whose strokes match the concept's icon language (24 viewport,
1.75 stroke, round caps/joins), and writes app/src/main/java/com/singu/xfriend/XfIcons.kt.
"""
from __future__ import annotations

import re
from pathlib import Path

SOURCE = Path(r"D:/octop/docs/design/redesign-concept.html")
TARGET = Path(r"D:/xfriend-suite/app/src/main/java/com/singu/xfriend/XfIcons.kt")
STROKE_WIDTH = 1.75


def f(value: str, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def parse_attrs(raw: str) -> dict[str, str]:
    return {k: v for k, v in re.findall(r'([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*"([^"]*)"', raw)}


def circle_path(cx: float, cy: float, r: float) -> str:
    return (
        f"M{cx - r:g},{cy:g}"
        f"A{r:g},{r:g} 0 1 0 {cx + r:g},{cy:g}"
        f"A{r:g},{r:g} 0 1 0 {cx - r:g},{cy:g}Z"
    )


def rect_path(x: float, y: float, w: float, h: float, rx: float, ry: float) -> str:
    if rx <= 0 and ry <= 0:
        return f"M{x:g},{y:g}H{x + w:g}V{y + h:g}H{x:g}Z"
    rx = rx if rx > 0 else ry
    ry = ry if ry > 0 else rx
    rx = min(rx, w / 2)
    ry = min(ry, h / 2)
    return (
        f"M{x + rx:g},{y:g}H{x + w - rx:g}"
        f"A{rx:g},{ry:g} 0 0 1 {x + w:g},{y + ry:g}V{y + h - ry:g}"
        f"A{rx:g},{ry:g} 0 0 1 {x + w - rx:g},{y + h:g}H{x + rx:g}"
        f"A{rx:g},{ry:g} 0 0 1 {x:g},{y + h - ry:g}V{y + ry:g}"
        f"A{rx:g},{ry:g} 0 0 1 {x + rx:g},{y:g}Z"
    )


def points_path(points: str, close: bool) -> str:
    nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", points)]
    if len(nums) < 4:
        return ""
    d = f"M{nums[0]:g},{nums[1]:g}"
    for i in range(2, len(nums) - 1, 2):
        d += f"L{nums[i]:g},{nums[i + 1]:g}"
    return d + ("Z" if close else "")


def extract_paths(body: str) -> list[tuple[str, bool, float]]:
    """Return (pathData, filled, alpha) triples in source order."""
    paths: list[tuple[str, bool, float]] = []
    for match in re.finditer(r"<(path|circle|rect|line|polyline|polygon|ellipse)\b([^>]*?)/?>", body, re.S):
        tag = match.group(1)
        attrs = parse_attrs(match.group(2))
        fill = attrs.get("fill", "none").strip().lower()
        filled = fill not in ("none", "", "transparent")
        alpha = f(attrs.get("opacity", "1"), 1.0)
        d = ""
        if tag == "path":
            d = attrs.get("d", "").strip()
        elif tag == "circle":
            d = circle_path(f(attrs["cx"]), f(attrs["cy"]), f(attrs["r"]))
        elif tag == "ellipse":
            rx, ry = f(attrs["rx"]), f(attrs["ry"])
            cx, cy = f(attrs["cx"]), f(attrs["cy"])
            d = (
                f"M{cx - rx:g},{cy:g}"
                f"A{rx:g},{ry:g} 0 1 0 {cx + rx:g},{cy:g}"
                f"A{rx:g},{ry:g} 0 1 0 {cx - rx:g},{cy:g}Z"
            )
        elif tag == "rect":
            d = rect_path(
                f(attrs.get("x")), f(attrs.get("y")),
                f(attrs.get("width")), f(attrs.get("height")),
                f(attrs.get("rx")), f(attrs.get("ry")),
            )
        elif tag == "line":
            d = f"M{f(attrs['x1']):g},{f(attrs['y1']):g}L{f(attrs['x2']):g},{f(attrs['y2']):g}"
        elif tag in ("polyline", "polygon"):
            d = points_path(attrs.get("points", ""), tag == "polygon")
        if d:
            paths.append((d, filled, alpha))
    return paths


def kt_name(symbol_id: str) -> str:
    raw = symbol_id[2:] if symbol_id.startswith("i-") else symbol_id
    parts = [p for p in re.split(r"[-_]+", raw) if p]
    return "".join(p[:1].upper() + p[1:] for p in parts)


def kt_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def main() -> None:
    html = SOURCE.read_text(encoding="utf-8")
    symbols = re.findall(r'<symbol id="([^"]+)" viewBox="[^"]*">(.*?)</symbol>', html, re.S)
    if not symbols:
        raise SystemExit("no symbols found")
    entries: list[tuple[str, list[tuple[str, bool, float]]]] = []
    for symbol_id, body in symbols:
        paths = extract_paths(body)
        if paths:
            entries.append((kt_name(symbol_id), paths))
    entries.sort(key=lambda item: item[0])

    lines: list[str] = []
    lines.append("package com.singu.xfriend")
    lines.append("")
    lines.append("import androidx.compose.ui.graphics.Color")
    lines.append("import androidx.compose.ui.graphics.SolidColor")
    lines.append("import androidx.compose.ui.graphics.StrokeCap")
    lines.append("import androidx.compose.ui.graphics.StrokeJoin")
    lines.append("import androidx.compose.ui.graphics.vector.ImageVector")
    lines.append("import androidx.compose.ui.graphics.vector.PathParser")
    lines.append("import androidx.compose.ui.unit.dp")
    lines.append("")
    lines.append("/**")
    lines.append(" * Singu Xfriend 重塑概念稿 v1 的线性图标语言（24 视窗、1.75 描边、圆头圆角）。")
    lines.append(" *")
    lines.append(" * 由 scripts/generate_xf_icons.py 从概念稿内联 SVG 自动生成，请勿手改；")
    lines.append(" * 需要新图标时先改概念稿再重新生成。图标统一使用 Icon(tint = ...) 着色。")
    lines.append(" */")
    lines.append("internal object XfIcons {")
    for name, paths in entries:
        lines.append(f'    val {name}: ImageVector by lazy {{ xfVector("{name}", listOf(')
        for data, filled, alpha in paths:
            alpha_arg = f", alpha = {alpha:g}f" if abs(alpha - 1.0) > 1e-6 else ""
            fill_arg = ", fill = true" if filled else ""
            lines.append(f'        XfPath("{kt_string(data)}"{fill_arg}{alpha_arg}),')
        lines.append("    )) }")
    lines.append("}")
    lines.append("")
    lines.append("private data class XfPath(val data: String, val fill: Boolean = false, val alpha: Float = 1f)")
    lines.append("")
    lines.append("private fun xfVector(name: String, paths: List<XfPath>): ImageVector {")
    lines.append("    val builder = ImageVector.Builder(")
    lines.append("        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,")
    lines.append("        viewportWidth = 24f, viewportHeight = 24f,")
    lines.append("    )")
    lines.append("    paths.forEach { path ->")
    lines.append("        val nodes = PathParser().parsePathString(path.data).toNodes()")
    lines.append("        if (path.fill) {")
    lines.append("            builder.addPath(nodes, fill = SolidColor(Color.Black), fillAlpha = path.alpha, stroke = null)")
    lines.append("        } else {")
    lines.append("            builder.addPath(")
    lines.append("                nodes,")
    lines.append("                fill = null,")
    lines.append("                stroke = SolidColor(Color.Black),")
    lines.append(f"                strokeLineWidth = {STROKE_WIDTH}f,")
    lines.append("                strokeLineCap = StrokeCap.Round,")
    lines.append("                strokeLineJoin = StrokeJoin.Round,")
    lines.append("            )")
    lines.append("        }")
    lines.append("    }")
    lines.append("    return builder.build()")
    lines.append("}")
    lines.append("")
    TARGET.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {TARGET} with {len(entries)} icons")


if __name__ == "__main__":
    main()
