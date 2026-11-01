package dev.owl.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.model.ThemeMode
import dev.owl.app.ui.OwlRoot
import dev.owl.app.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not `savedInstanceState == null`: after Android killed the process in the background the
        // activity comes back with saved state, and that is still the user opening the app (A2a).
        appContainer.onScreenCreated(fresh = savedInstanceState == null)
        setContent {
            // Until the setting loads, the configuration already follows it (AppContainer.followThemeSetting).
            val mode: ThemeMode? by appContainer.settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = null)
            val systemDark = isSystemInDarkTheme()
            val dark = mode?.isDark(systemDark) ?: systemDark
            // Status/navigation bar icons follow the app theme, not the system one.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            OwlRoot(appContainer, darkTheme = dark)
        }
    }

    /** Back from the background with the same activity (not a new start: that is onCreate). */
    override fun onRestart() {
        super.onRestart()
        appContainer.onAppResumed()
    }

    private companion object {
        // enableEdgeToEdge()'s own defaults (3-button navigation only).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
