package io.github.thanhngochoang.voicetrans.feature.overlay

import io.github.thanhngochoang.voicetrans.core.settings.OverlayPosition
import kotlin.math.min
import kotlin.math.roundToInt

/** The screen minus system bars and display cutout (px), measured ignoring bar visibility so it never jumps. */
data class SafeArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class Orientation { PORTRAIT, LANDSCAPE }

/** min(safe width - 32 dp, 720 dp). */
fun overlayWidth(safe: SafeArea, density: Float): Int = min(safe.width - (32 * density).roundToInt(), (720 * density).roundToInt())

/**
 * Top y of the box, always inside [safe]. A [customY] fraction (0 = top, 1 = bottom of the free range) wins over the
 * preset. BOTTOM keeps 24 dp (portrait) or 16 dp (landscape) above the safe bottom, TOP 24 dp below the safe top.
 */
fun overlayY(
    position: OverlayPosition,
    customY: Float?,
    orientation: Orientation,
    safe: SafeArea,
    boxHeightPx: Int,
    density: Float,
): Int {
    val free = (safe.height - boxHeightPx).coerceAtLeast(0)
    val y = when {
        customY != null -> safe.top + (customY.coerceIn(0f, 1f) * free).roundToInt()
        position == OverlayPosition.TOP -> safe.top + (24 * density).roundToInt()
        position == OverlayPosition.CENTER -> safe.top + free / 2
        else -> safe.bottom - ((if (orientation == Orientation.PORTRAIT) 24 else 16) * density).roundToInt() - boxHeightPx
    }
    return y.coerceIn(safe.top, safe.top + free)
}

/** 35% of the safe height. */
fun maxBoxHeight(safe: SafeArea): Int = (safe.height * 0.35f).roundToInt()

/** Drops the oldest lines first until the rest fits in [maxPx]; the newest line is always kept. */
fun fitLines(lines: List<OverlayLine>, lineHeightsPx: List<Int>, maxPx: Int): List<OverlayLine> {
    if (lines.isEmpty()) return lines
    var total = 0
    var first = lines.size
    for (i in lines.indices.reversed()) {
        total += lineHeightsPx[i]
        if (total > maxPx && first != lines.size) break
        first = i
    }
    return lines.subList(first, lines.size)
}

/** Android passes touches through an overlay only when its window alpha is at most 0.8. */
fun windowAlpha(locked: Boolean): Float = if (locked) 0.8f else 1f
