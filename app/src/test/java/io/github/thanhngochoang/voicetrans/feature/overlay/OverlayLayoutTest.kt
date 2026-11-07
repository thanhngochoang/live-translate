package io.github.thanhngochoang.voicetrans.feature.overlay

import io.github.thanhngochoang.voicetrans.core.settings.OverlayPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {
    private fun line(id: Long) = OverlayLine(id, "t$id", false, false)

    @Test fun bottomPreset_portrait_sitsAboveSafeBottom() =
        assertEquals(2280 - 72 - 200, overlayY(OverlayPosition.BOTTOM, null, Orientation.PORTRAIT, SafeArea(0, 80, 1080, 2280), 200, 3f))

    @Test fun bottomPreset_landscape_uses16dp() =
        assertEquals(1080 - 48 - 200, overlayY(OverlayPosition.BOTTOM, null, Orientation.LANDSCAPE, SafeArea(90, 0, 2280, 1080), 200, 3f))

    @Test fun topPreset_is24dpBelowSafeTop() =
        assertEquals(80 + 72, overlayY(OverlayPosition.TOP, null, Orientation.PORTRAIT, SafeArea(0, 80, 1080, 2280), 200, 3f))

    @Test fun centerPreset_isCentered() =
        assertEquals(80 + (2200 - 200) / 2, overlayY(OverlayPosition.CENTER, null, Orientation.PORTRAIT, SafeArea(0, 80, 1080, 2280), 200, 3f))

    @Test fun customY_mapsWithinSafeArea() {
        val safe = SafeArea(0, 80, 1080, 2280)
        assertEquals(safe.top, overlayY(OverlayPosition.BOTTOM, 0f, Orientation.PORTRAIT, safe, 200, 3f))
        assertEquals(safe.bottom - 200, overlayY(OverlayPosition.BOTTOM, 1f, Orientation.PORTRAIT, safe, 200, 3f))
        assertEquals(80 + (2200 - 200) / 2, overlayY(OverlayPosition.BOTTOM, 0.5f, Orientation.PORTRAIT, safe, 200, 3f))
    }

    @Test fun y_neverLeavesSafeArea() {
        val safe = SafeArea(0, 80, 1080, 400)
        val y = overlayY(OverlayPosition.BOTTOM, null, Orientation.PORTRAIT, safe, 900, 3f)
        assertTrue(y >= safe.top)
    }

    @Test fun width_landscapePhone_capped720dp() = assertEquals(720 * 3, overlayWidth(SafeArea(0, 0, 2400, 1080), 3f))

    @Test fun width_portrait_safeWidthMinus32dp() = assertEquals(1080 - 96, overlayWidth(SafeArea(0, 0, 1080, 2280), 3f))

    @Test fun fitLines_dropsOldestKeepsNewest() {
        val lines = (1L..4L).map(::line)
        assertEquals(listOf(3L, 4L), fitLines(lines, listOf(80, 80, 80, 80), 200).map { it.id })
        assertEquals(listOf(4L), fitLines(lines.takeLast(1), listOf(500), 200).map { it.id })
    }

    @Test fun fitLines_fitsAllWhenRoom() = assertEquals(3, fitLines((1L..3L).map(::line), listOf(50, 50, 50), 400).size)

    @Test fun maxBoxHeight_is35Percent() = assertEquals(378, maxBoxHeight(SafeArea(0, 0, 2000, 1080)))

    @Test fun locked_capsAlphaAt0_8() {
        assertEquals(0.8f, windowAlpha(true))
        assertEquals(1f, windowAlpha(false))
    }
}
