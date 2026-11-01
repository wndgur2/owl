package dev.owl.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "owl_settings")

/** Small key-value settings (DataStore). Excluded from backup like everything else (decisions #22). */
class SettingsStore(private val store: DataStore<Preferences>) : SettingsRepository, RegistrationStore {
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val retention = stringPreferencesKey("retention")
        val location = booleanPreferencesKey("location_enabled")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme_mode")
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val associationId = intPreferencesKey("device_association_id")
        val fake = booleanPreferencesKey("device_fake")
        val lastSync = longPreferencesKey("last_sync_ms")
        val lastSeen = longPreferencesKey("last_seen_ms")
        val disconnected = booleanPreferencesKey("disconnected")
    }

    override val retention: Flow<RetentionPolicy> = store.data.map { p ->
        p[Keys.retention]?.let { runCatching { RetentionPolicy.valueOf(it) }.getOrNull() } ?: RetentionPolicy.DAYS_30
    }.distinctUntilChanged()

    override suspend fun setRetention(policy: RetentionPolicy) {
        store.edit { it[Keys.retention] = policy.name }
    }

    override val locationEnabled: Flow<Boolean> = store.data.map { it[Keys.location] ?: true }.distinctUntilChanged()

    override suspend fun setLocationEnabled(enabled: Boolean) {
        store.edit { it[Keys.location] = enabled }
    }

    override val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.onboarding] ?: false }.distinctUntilChanged()

    override suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[Keys.onboarding] = done }
    }

    override val themeMode: Flow<ThemeMode> = store.data.map { p ->
        p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.theme] = mode.name }
    }

    override val device: Flow<RegisteredDevice?> = store.data.map { p ->
        val address = p[Keys.address] ?: return@map null
        RegisteredDevice(address, p[Keys.name] ?: address, p[Keys.associationId], p[Keys.fake] ?: false)
    }.distinctUntilChanged()

    override suspend fun current(): RegisteredDevice? = device.first()

    override suspend fun save(device: RegisteredDevice) {
        store.edit {
            it[Keys.address] = device.address
            it[Keys.name] = device.name
            if (device.associationId != null) it[Keys.associationId] = device.associationId else it.remove(Keys.associationId)
            it[Keys.fake] = device.isFake
            it.remove(Keys.lastSync)
            it.remove(Keys.lastSeen)
            it.remove(Keys.disconnected)
        }
    }

    override suspend fun clear() {
        store.edit {
            listOf(Keys.address, Keys.name, Keys.associationId, Keys.fake, Keys.lastSync, Keys.lastSeen, Keys.disconnected)
                .forEach { k -> it.remove(k) }
        }
    }

    data class SyncMeta(val lastSyncAtMs: Long?, val lastSeenAtMs: Long?, val disconnected: Boolean)

    val syncMeta: Flow<SyncMeta> = store.data.map { SyncMeta(it[Keys.lastSync], it[Keys.lastSeen], it[Keys.disconnected] ?: false) }
        .distinctUntilChanged()

    /** The pendant answered (connect + STATUS worked). */
    suspend fun markSeen(timeMs: Long) {
        store.edit { it[Keys.lastSeen] = timeMs; it[Keys.disconnected] = false }
    }

    suspend fun markSynced(timeMs: Long) {
        store.edit { it[Keys.lastSync] = timeMs; it[Keys.lastSeen] = timeMs; it[Keys.disconnected] = false }
    }

    /** decisions.md #16: failed after retries → "연결 끊김" right away. */
    suspend fun markDisconnected() {
        store.edit { it[Keys.disconnected] = true }
    }
}
