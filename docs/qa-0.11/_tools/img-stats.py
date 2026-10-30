#!/usr/bin/env python
"""img-stats.py — 截图证据统计（只读图片，不修改）。

输出 JSON：尺寸、平均亮度、近黑/近白占比、Top 颜色、指定采样点颜色、
以及「暖纸底色」#F6F5F1 与「深色画布」#0E0F12 的像素占比，用于佐证主题/底色。

用法:
  python img-stats.py a.png b.png
  python img-stats.py a.png --points "540,300;540,1200" --top 8 --pretty
"""
import argparse
import json
import sys
from collections import Counter

from PIL import Image

PAPER_LIGHT = (0xF6, 0xF5, 0xF1)
CANVAS_DARK = (0x0E, 0x0F, 0x12)


def hexc(rgb):
    return "#%02X%02X%02X" % (int(rgb[0]), int(rgb[1]), int(rgb[2]))


def near(rgb, target, tol):
    return all(abs(int(rgb[i]) - target[i]) <= tol for i in range(3))


def luma(rgb):
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]


def analyze(path, points, top_n, tol):
    im = Image.open(path)
    mode = im.mode
    im = im.convert("RGB")
    w, h = im.size
    # 缩小采样，足够代表整屏
    sw, sh = max(1, w // 6), max(1, h // 6)
    small = im.resize((sw, sh), Image.Resampling.BOX)
    raw = small.tobytes()
    px = [(raw[i], raw[i + 1], raw[i + 2]) for i in range(0, len(raw), 3)]
    n = len(px)
    mean = tuple(round(sum(p[i] for p in px) / n, 1) for i in range(3))
    lum = [luma(p) for p in px]
    near_black = sum(1 for v in lum if v < 16) / n
    near_white = sum(1 for v in lum if v > 240) / n
    paper = sum(1 for p in px if near(p, PAPER_LIGHT, tol)) / n
    dark = sum(1 for p in px if near(p, CANVAS_DARK, tol)) / n
    q = Counter((p[0] // 8 * 8, p[1] // 8 * 8, p[2] // 8 * 8) for p in px)
    top = [{"color": hexc(c), "pct": round(cnt / n * 100, 2)} for c, cnt in q.most_common(top_n)]
    out = {
        "file": path,
        "mode": mode,
        "size": [w, h],
        "mean_rgb": [round(v) for v in mean],
        "mean_luma": round(sum(lum) / n, 1),
        "near_black_pct": round(near_black * 100, 2),
        "near_white_pct": round(near_white * 100, 2),
        "paper_light_pct": round(paper * 100, 2),
        "canvas_dark_pct": round(dark * 100, 2),
        "top_colors": top,
    }
    if points:
        samples = []
        for ptxt in points.split(";"):
            ptxt = ptxt.strip()
            if not ptxt:
                continue
            xs, ys = ptxt.split(",")
            x, y = int(xs), int(ys)
            x = max(0, min(w - 1, x))
            y = max(0, min(h - 1, y))
            rgb = im.getpixel((x, y))
            samples.append({"xy": [x, y], "rgb": list(rgb), "hex": hexc(rgb)})
        out["samples"] = samples
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="+")
    ap.add_argument("--points", default="")
    ap.add_argument("--top", type=int, default=8)
    ap.add_argument("--tol", type=int, default=12)
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args()
    results = []
    for p in args.images:
        try:
            results.append(analyze(p, args.points, args.top, args.tol))
        except Exception as exc:  # noqa: BLE001
            results.append({"file": p, "error": str(exc)})
    payload = results[0] if len(results) == 1 else results
    print(json.dumps(payload, ensure_ascii=False, indent=2 if args.pretty else None))
    return 0


if __name__ == "__main__":
    sys.exit(main())
