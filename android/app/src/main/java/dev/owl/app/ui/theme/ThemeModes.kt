package dev.owl.app.ui.theme

import android.app.UiModeManager
import dev.owl.app.model.ThemeMode

/** Whether [ThemeMode] draws dark; [systemDark] is `isSystemInDarkTheme()`. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemDark
}

/** Value for [UiModeManager.setApplicationNightMode] (the per-app `-night` qualifier, kept by Android). */
val ThemeMode.nightMode: Int get() = when (this) {
    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
}
