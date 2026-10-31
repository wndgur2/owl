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
}
