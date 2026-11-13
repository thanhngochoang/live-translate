package io.github.thanhngochoang.voicetrans.feature.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode

val LocalVtScheme = staticCompositionLocalOf { VtPalette.Dark }

/** Access to the tokens that M3 has no role for (banner, stop button). */
object VtTheme {
    val colors: VtScheme @Composable get() = LocalVtScheme.current
}

private fun VtScheme.toM3(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        background = background, onBackground = onSurface,
        surface = surface, onSurface = onSurface, surfaceVariant = surface, onSurfaceVariant = onSurfaceVariant,
        surfaceContainer = surface, surfaceContainerLow = surface, surfaceContainerHigh = surface, surfaceContainerHighest = surface,
        outlineVariant = outlineVariant, outline = onSurfaceVariant,
        primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    )
}

/** Dark first, no dynamic color. */
@Composable
fun VoiceTransTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = if (dark) VtPalette.Dark else VtPalette.Light
    CompositionLocalProvider(LocalVtScheme provides scheme) {
        MaterialTheme(colorScheme = scheme.toM3(), typography = VtTypography, content = content)
    }
}
