package com.singu.xfriend

internal data class ReviewImageRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val label get() = "$left,$top,${right - left},${bottom - top}"
    val suffix get() = "tile-$left-$top-$right-$bottom"
}

/** Crop at source resolution; narrow long screenshots are never shrunk to fit a single square. */
internal fun reviewImageRegions(width: Int, height: Int): List<ReviewImageRegion> {
    require(width > 0 && height > 0)
    val result = mutableListOf<ReviewImageRegion>()
    var top = 0
    while (top < height) {
        val bottom = minOf(height.toLong(), top.toLong() + 2000).toInt()
        var left = 0
        while (left < width) {
            val right = minOf(width.toLong(), left.toLong() + 1800).toInt()
            result.add(ReviewImageRegion(left, top, right, bottom))
            require(result.size <= 1000) { "图片尺寸过大，请按会话拆分" }
            if (right == width) break
            left = right - 128
        }
        if (bottom == height) break
        top = bottom - 128
    }
    return result
}
internal fun reviewAssetRegion(name: String): String = Regex("-tile-(\\d+)-(\\d+)-(\\d+)-(\\d+)\\.jpg$")
    .find(name)?.groupValues?.drop(1)?.map(String::toInt)?.let { (left, top, right, bottom) ->
        "$left,$top,${right - left},${bottom - top}"
    }.orEmpty()
