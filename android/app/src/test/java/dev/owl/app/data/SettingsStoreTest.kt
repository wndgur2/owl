package dev.owl.app.data

import android.app.UiModeManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.owl.app.model.ThemeMode
import dev.owl.app.ui.theme.isDark
import dev.owl.app.ui.theme.nightMode
import dev.owl.app.usage.ClockReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "owl_settings.preferences_pb") }

    /** Opens the same file the way the app does after a restart; close it before opening another. */
    private suspend fun <T> withStore(block: suspend (SettingsStore, DataStore<Preferences>) -> T): T {
        val job = Job()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        return try {
            block(SettingsStore(dataStore), dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun themeDefaultsToDark() = runTest {
        withStore { store, _ -> assertEquals(ThemeMode.DARK, store.themeMode.first()) }
    }

    @Test
    fun themeSurvivesRestart() = runTest {
        withStore { store, _ -> store.setThemeMode(ThemeMode.LIGHT) }
        withStore { store, _ -> assertEquals(ThemeMode.LIGHT, store.themeMode.first()) }
        withStore { store, _ -> store.setThemeMode(ThemeMode.SYSTEM) }
        withStore { store, _ -> assertEquals(ThemeMode.SYSTEM, store.themeMode.first()) }
    }

    @Test
    fun unknownStoredThemeFallsBackToDark() = runTest {
        withStore { _, raw -> raw.edit { it[stringPreferencesKey("theme_mode")] = "SEPIA" } }
        withStore { store, _ -> assertEquals(ThemeMode.DARK, store.themeMode.first()) }
    }

    @Test
    fun themeChangeKeepsOtherSettings() = runTest {
        withStore { store, _ -> store.setLocationEnabled(false) }
        withStore { store, _ -> store.setThemeMode(ThemeMode.LIGHT) }
        withStore { store, _ -> assertFalse(store.locationEnabled.first()) }
    }

    @Test
    fun appUsageIsOffByDefaultAndForgetsItsPositionWhenTurnedOff() = runTest {
        val clock = ClockReading(bootCount = 3, wallMinusElapsedMs = 500)
        withStore { store, _ ->
            assertFalse(store.appUsageEnabled.first())
            assertEquals(null, store.appUsageCursor())
            assertEquals(null, store.appUsageClock())
            store.setAppUsageCursor(1_000, clock)
            store.setAppUsageEnabled(true)
        }
        withStore { store, _ ->
            assertTrue(store.appUsageEnabled.first())
            assertEquals(1_000L, store.appUsageCursor())
            assertEquals(clock, store.appUsageClock())
            store.setAppUsageEnabled(false)
            assertFalse(store.appUsageEnabled.first())
            assertEquals(null, store.appUsageCursor())
            assertEquals(null, store.appUsageClock())
        }
    }

    @Test
    fun themeModeMapping() {
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.DARK.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.LIGHT.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.SYSTEM.nightMode)
    }
}
