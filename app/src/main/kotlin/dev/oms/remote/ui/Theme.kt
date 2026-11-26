package dev.oms.remote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/*
 * Theme: Material 3 with dynamic color on Android 12+, and a calm slate-blue fallback scheme.
 * Status colors (live / warning / parked) are the only colors defined outside the color scheme.
 */

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF3B5BA5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001946),
    inversePrimary = Color(0xFFB0C6FF),
    secondary = Color(0xFF575E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF2F6A5F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB4EFE1),
    onTertiaryContainer = Color(0xFF00201B),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceTint = Color(0xFF3B5BA5),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF0D2F68),
    primaryContainer = Color(0xFF22448C),
    onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF3B5BA5),
    secondary = Color(0xFFC0C6DC),
    onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF98D3C5),
    onTertiary = Color(0xFF003730),
    tertiaryContainer = Color(0xFF145147),
    onTertiaryContainer = Color(0xFFB4EFE1),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFB0C6FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** Semantic status colors that Material's scheme has no slot for. */
@Immutable
internal data class StatusColors(
    /** Connected / done / running agent. */
    val live: Color,
    /** Reconnecting / near context limit. */
    val warn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    /** Parked agents and other inactive states. */
    val parked: Color,
)

private val LightStatus = StatusColors(
    live = Color(0xFF2E7D32),
    warn = Color(0xFFA15C00),
    warnContainer = Color(0xFFFFE2C2),
    onWarnContainer = Color(0xFF3A2200),
    parked = Color(0xFF85878F),
)

private val DarkStatus = StatusColors(
    live = Color(0xFF7DD58C),
    warn = Color(0xFFFFB866),
    warnContainer = Color(0xFF4D3100),
    onWarnContainer = Color(0xFFFFDDB8),
    parked = Color(0xFF8F9099),
)

internal val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Theme extras: status colors and monospace text styles. */
internal object OmsStyle {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current

    /** Tool output, args JSON, approval bodies. */
    val mono: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )

    /** One-line summaries in tool cards. */
    val monoSmall: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )

    /** Transcript body text (slightly denser than bodyLarge). */
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
}

@Composable
internal fun OmsThemeImpl(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = remember(dark, context) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> DarkColors
            else -> LightColors
        }
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
