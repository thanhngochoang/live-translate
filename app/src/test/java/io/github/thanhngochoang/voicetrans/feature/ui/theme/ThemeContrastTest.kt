package io.github.thanhngochoang.voicetrans.feature.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).coerceAtLeast(luminance(b)) to luminance(a).coerceAtMost(luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun pairs(s: VtScheme) = listOf(
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurface/background" to (s.onSurface to s.background),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "primary/surface" to (s.primary to s.surface),
        "secondary/surface" to (s.secondary to s.surface),
        "error/surface" to (s.error to s.surface),
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary/secondary" to (s.onSecondary to s.secondary),
        "onError/errorContainer (Stop button)" to (s.onErrorContainer to s.errorContainer),
        "onBanner/bannerContainer" to (s.onBanner to s.bannerContainer),
    )

    private fun check(name: String, s: VtScheme) {
        for ((label, p) in pairs(s)) {
            val c = contrast(p.first, p.second)
            assertTrue("$name $label contrast %.2f < 4.5".format(c), c >= 4.5)
        }
    }

    @Test fun dark_allTextPairs_atLeast4_5() = check("dark", VtPalette.Dark)

    @Test fun light_allTextPairs_atLeast4_5() = check("light", VtPalette.Light)

    @Test fun darkBackground_isPureBlack_forOled() = assertTrue(VtPalette.Dark.background == Color(0xFF000000))

    @Test fun startButtonIsExactLogoBlue_stopIsExactLogoRed() {
        for (s in listOf(VtPalette.Dark, VtPalette.Light)) {
            assertTrue(s.primaryContainer == Color(0xFF0166F6))
            assertTrue(s.errorContainer == Color(0xFFC60516))
        }
    }
}
