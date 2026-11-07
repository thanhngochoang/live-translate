package io.github.thanhngochoang.voicetrans.core.settings

enum class OverlayPosition { TOP, CENTER, BOTTOM }

data class OverlaySettings(
    val enabled: Boolean = true,
    /** Background opacity while translating, 0..100 in steps of 10. */
    val bgOpacityPercent: Int = 30,
    /** 14..28. */
    val textSizeSp: Int = 18,
    /** 1..3. */
    val maxLines: Int = 2,
    val showSource: Boolean = false,
    val position: OverlayPosition = OverlayPosition.BOTTOM,
    /** Dragged position as a fraction 0..1 of the safe height; null means use [position]. Per orientation. */
    val customYPortrait: Float? = null,
    val customYLandscape: Float? = null,
    /** 0 = never, 5, 8 or 15. */
    val autoHideSec: Int = 8,
    val locked: Boolean = false,
)
