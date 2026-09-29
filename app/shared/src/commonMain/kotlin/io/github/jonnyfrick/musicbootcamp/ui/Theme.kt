package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The platform's own colours where it has some (Android 12+: from the wallpaper), else null. */
@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme?

/** Colours Material has no role for: a right answer, next to [ColorScheme.error] for a wrong one. */
@Immutable
data class ExtendedColors(val correct: Color, val onCorrectContainer: Color, val correctContainer: Color)

private val LightExtended = ExtendedColors(correct = Color(0xFF2E6B3A), onCorrectContainer = Color(0xFF0E5224), correctContainer = Color(0xFFB2F1B6))
private val DarkExtended = ExtendedColors(correct = Color(0xFF96D59B), onCorrectContainer = Color(0xFFB2F1B6), correctContainer = Color(0xFF14522A))

internal val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** A calm blue, generated from one seed colour with Material's theme builder. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF435E91), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF2A4678),
    secondary = Color(0xFF565F71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF705575), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD), onTertiaryContainer = Color(0xFF573E5C),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2F3036), inverseOnSurface = Color(0xFFF0F0F7), inversePrimary = Color(0xFFADC6FF),
    surfaceDim = Color(0xFFD9D9E0), surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF102F60),
    primaryContainer = Color(0xFF2A4678), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC), onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0), onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C), onTertiaryContainer = Color(0xFFFAD8FD),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
    inverseSurface = Color(0xFFE2E2E9), inverseOnSurface = Color(0xFF2F3036), inversePrimary = Color(0xFF435E91),
    surfaceDim = Color(0xFF111318), surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E2025), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

@Composable
internal fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = platformColorScheme(dark) ?: if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

internal val MaterialTheme.extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current
