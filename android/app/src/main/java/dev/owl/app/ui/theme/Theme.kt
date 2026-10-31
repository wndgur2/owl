package dev.owl.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 baseline colour roles (docs/design/android uses them verbatim, e.g. #6750A4 primary).
 * Dynamic colour is off on purpose so the app looks like the design on every phone.
 *
 * The only deviation from the stock schemes is `background`: the design's root background is
 * SurfaceContainerLowest (#FFFFFF) in light and Surface (#141218) in dark, so screens can simply use
 * `colorScheme.background` (Scaffold's default container colour).
 */
private val LightColors: ColorScheme = lightColorScheme().let { it.copy(background = it.surfaceContainerLowest) }

private val DarkColors: ColorScheme = darkColorScheme().let { it.copy(background = it.surface) }

@Composable
fun OwlTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** Disabled content colour as M3 specifies it (onSurface at 38 %). */
val ColorScheme.disabledContent: Color get() = onSurface.copy(alpha = 0.38f)

/** Disabled container colour (onSurface at 12 %). */
val ColorScheme.disabledContainer: Color get() = onSurface.copy(alpha = 0.12f)
