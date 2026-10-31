package dev.owl.app

import android.content.Context
import dev.owl.app.model.AudioPlayer
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.Exporter
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.StubAudioPlayer
import dev.owl.app.model.StubDeviceController
import dev.owl.app.model.StubDeviceRegistrar
import dev.owl.app.model.StubExporter
import dev.owl.app.model.StubSettingsRepository
import dev.owl.app.model.StubTimelineRepository
import dev.owl.app.model.TimelineRepository
import dev.owl.app.data.RegisteredDevice
import dev.owl.app.data.RegistrationStore
import dev.owl.app.location.LocationSink
import dev.owl.app.sync.SyncRunner
import dev.owl.app.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual dependency injection: one instance per process, created in [OwlApp].
 * Anything that needs a dependency gets it from here (`(context.applicationContext as OwlApp).container`).
 * No DI framework on purpose — every wire is visible in this one file.
 */
class AppContainer(private val context: Context) {
    val timelineRepository: TimelineRepository by lazy { StubTimelineRepository() }
    val deviceController: DeviceController by lazy { StubDeviceController() }
    val settingsRepository: SettingsRepository by lazy { StubSettingsRepository() }
    val deviceRegistrar: DeviceRegistrar by lazy { StubDeviceRegistrar() }
    val exporter: Exporter by lazy { StubExporter() }

    /** A new player per Entry screen; the caller releases it. */
    fun newAudioPlayer(): AudioPlayer = StubAudioPlayer()

    val syncRunner: SyncRunner by lazy {
        object : SyncRunner {
            override suspend fun sync(trigger: SyncTrigger) = false
            override val devicePresent: StateFlow<Boolean> = MutableStateFlow(false)
            override fun setDevicePresent(present: Boolean) = Unit
        }
    }

    val registrationStore: RegistrationStore by lazy {
        object : RegistrationStore {
            private val state = MutableStateFlow<RegisteredDevice?>(null)
            override val device: Flow<RegisteredDevice?> = state
            override suspend fun current() = state.value
            override suspend fun save(device: RegisteredDevice) { state.value = device }
            override suspend fun clear() { state.value = null }
        }
    }

    val locationSink: LocationSink by lazy { LocationSink { _, _, _, _ -> } }
}
