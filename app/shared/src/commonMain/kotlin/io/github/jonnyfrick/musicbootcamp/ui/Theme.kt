package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours Material has no role for: a right answer, next to [androidx.compose.material3.ColorScheme.error]
 * for a wrong one, and the keys of the drawn keyboard.
 */
@Immutable
data class ExtendedColors(
    val correct: Color,
    val onCorrectContainer: Color,
    val correctContainer: Color,
    val whiteKey: Color,
    val blackKey: Color,
)

private val LightExtended = ExtendedColors(
    correct = Color(0xFF237A55), onCorrectContainer = Color(0xFF0E4A31), correctContainer = Color(0xFFD3EEDF),
    whiteKey = Color(0xFFFFFFFF), blackKey = Color(0xFF1C2433),
)
private val DarkExtended = ExtendedColors(
    correct = Color(0xFF5FCB97), onCorrectContainer = Color(0xFFD3EEDF), correctContainer = Color(0xFF173A2A),
    whiteKey = Color(0xFFE9E5DC), blackKey = Color(0xFF0B0D12),
)

internal val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/**
 * Light: ink blue on manuscript paper, with amber (tertiary) for the streak and an orange-red for
 * mistakes that differs from the green of right answers in lightness too.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF34558B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4F2), onPrimaryContainer = Color(0xFF1B3561),
    secondary = Color(0xFF6B5E4B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1E3CC), onSecondaryContainer = Color(0xFF4A3F2E),
    tertiary = Color(0xFFA8620A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8E8CC), onTertiaryContainer = Color(0xFF5A3500),
    error = Color(0xFFB83C14), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE1D2), onErrorContainer = Color(0xFF6B2005),
    background = Color(0xFFFBF8F2), onBackground = Color(0xFF1C2433),
    surface = Color(0xFFFBF8F2), onSurface = Color(0xFF1C2433),
    surfaceVariant = Color(0xFFECE5D8), onSurfaceVariant = Color(0xFF596170),
    outline = Color(0xFF857F73), outlineVariant = Color(0xFFE3DCCF),
    inverseSurface = Color(0xFF2F3440), inverseOnSurface = Color(0xFFF3EFE7), inversePrimary = Color(0xFFADC6FF),
    surfaceDim = Color(0xFFE0DAD0), surfaceBright = Color(0xFFFBF8F2),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F2EA),
    surfaceContainer = Color(0xFFF2EDE3), surfaceContainerHigh = Color(0xFFECE6DB),
    surfaceContainerHighest = Color(0xFFE6E0D4),
)

/** Dark: a stage at night, the accents lighter so they glow on the dark ground. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB0E8), onPrimary = Color(0xFF0E2C5C),
    primaryContainer = Color(0xFF2A4678), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFD6C4A8), onSecondary = Color(0xFF3A2F1E),
    secondaryContainer = Color(0xFF52452F), onSecondaryContainer = Color(0xFFF1E3CC),
    tertiary = Color(0xFFF2B04A), onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF3D2E14), onTertiaryContainer = Color(0xFFF8E8CC),
    error = Color(0xFFFF8E5A), onError = Color(0xFF4A1600),
    errorContainer = Color(0xFF5A2410), onErrorContainer = Color(0xFFFFDBCB),
    background = Color(0xFF11141B), onBackground = Color(0xFFECE8E0),
    surface = Color(0xFF11141B), onSurface = Color(0xFFECE8E0),
    surfaceVariant = Color(0xFF2E3647), onSurfaceVariant = Color(0xFFA7AEBB),
    outline = Color(0xFF7F8796), outlineVariant = Color(0xFF2E3647),
    inverseSurface = Color(0xFFECE8E0), inverseOnSurface = Color(0xFF2B2F38), inversePrimary = Color(0xFF34558B),
    surfaceDim = Color(0xFF11141B), surfaceBright = Color(0xFF353B49),
    surfaceContainerLowest = Color(0xFF0B0D12), surfaceContainerLow = Color(0xFF161A23),
    surfaceContainer = Color(0xFF1A1F2B), surfaceContainerHigh = Color(0xFF232A39),
    surfaceContainerHighest = Color(0xFF2C3445),
)

/** The app's own colours on every platform (no wallpaper colours): they are part of its look. */
@Composable
internal fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}

internal val MaterialTheme.extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current
