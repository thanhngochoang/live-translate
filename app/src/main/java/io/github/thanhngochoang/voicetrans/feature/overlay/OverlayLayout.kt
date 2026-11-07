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

/**
 * Left x of the box. [customX] is a fraction of the free width (0 = safe left edge, 1 = right edge); null means
 * centered (0.5). A box wider than the safe area is centered.
 */
fun overlayX(customX: Float?, safe: SafeArea, boxWidthPx: Int): Int {
    val free = safe.width - boxWidthPx
    if (free <= 0) return safe.left + free / 2
    return safe.left + ((customX ?: 0.5f).coerceIn(0f, 1f) * free).roundToInt()
}

/** Inverse of [overlayX]: the fraction (0..1) for a box whose left edge is at [x]. Centered (0.5) when it cannot move. */
fun xFraction(x: Int, safe: SafeArea, boxWidthPx: Int): Float {
    val free = safe.width - boxWidthPx
    if (free <= 0) return 0.5f
    return ((x - safe.left).toFloat() / free).coerceIn(0f, 1f)
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
