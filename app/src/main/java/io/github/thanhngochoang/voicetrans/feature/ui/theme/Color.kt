package io.github.thanhngochoang.voicetrans.feature.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette tokens from docs/design/ui-spec.md (sampled from the logo). Components never use raw hex.
 * Blue is the only action color, violet marks the translated side, red is only for Stop and errors.
 */
data class VtScheme(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val error: Color,
    val onError: Color,
    /** The "Dừng" button: the logo's LIVE red. */
    val errorContainer: Color,
    val onErrorContainer: Color,
    /** Capture-blocked / stopped / exit-reason banner. */
    val bannerContainer: Color,
    val onBanner: Color,
    val isDark: Boolean,
)

object VtPalette {
    val Dark = VtScheme(
        background = Color(0xFF000000), surface = Color(0xFF0D1324),
        onSurface = Color(0xFFE8ECF5), onSurfaceVariant = Color(0xFF9BA6BF), outlineVariant = Color(0xFF2A3550),
        primary = Color(0xFF7CB0FF), onPrimary = Color(0xFF00163D),
        primaryContainer = Color(0xFF0166F6), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFC4A8FF), onSecondary = Color(0xFF1B0647),
        error = Color(0xFFFF8A8A), onError = Color(0xFF3A0006),
        errorContainer = Color(0xFFC60516), onErrorContainer = Color(0xFFFFFFFF),
        bannerContainer = Color(0xFF2A0A0E), onBanner = Color(0xFFFFB3B3),
        isDark = true,
    )

    val Light = VtScheme(
        background = Color(0xFFFFFFFF), surface = Color(0xFFF3F5FB),
        onSurface = Color(0xFF0B1530), onSurfaceVariant = Color(0xFF4F5B76), outlineVariant = Color(0xFFD5DBE8),
        primary = Color(0xFF0057D9), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF0166F6), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF5710DC), onSecondary = Color(0xFFFFFFFF),
        error = Color(0xFFC60516), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFC60516), onErrorContainer = Color(0xFFFFFFFF),
        bannerContainer = Color(0xFFFDE7E9), onBanner = Color(0xFF7A0010),
        isDark = false,
    )
}
