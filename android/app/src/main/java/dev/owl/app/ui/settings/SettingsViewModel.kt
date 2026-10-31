package dev.owl.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.AudioUsage
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.Exporter
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.TimelineRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val retention: RetentionPolicy? = null,
    val locationEnabled: Boolean = false,
    val registeredName: String? = null,
    /** Debug builds: the fake pendant can be used. */
    val fakeDeviceAvailable: Boolean = false,
    val usingFakeDevice: Boolean = false,
    val exporting: Boolean = false,
)

sealed interface SettingsEvent {
    data class Exported(val count: Int) : SettingsEvent
    data object ExportFailed : SettingsEvent
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val registrar: DeviceRegistrar,
    controller: DeviceController,
    private val exporter: Exporter,
) : ViewModel() {
    private val exporting = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow: Flow<SettingsEvent> = events.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settings.retention,
        settings.locationEnabled,
        registrar.registeredName,
        controller.state,
        exporting,
    ) { retention, location, name, device, busy ->
        SettingsUiState(
            retention = retention,
            locationEnabled = location,
            registeredName = name,
            fakeDeviceAvailable = registrar.fakeDeviceAvailable,
            usingFakeDevice = device.isFake,
            exporting = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(fakeDeviceAvailable = registrar.fakeDeviceAvailable))

    fun setLocationEnabled(on: Boolean) {
        viewModelScope.launch { settings.setLocationEnabled(on) }
    }

    fun export(uri: Uri) {
        exporting.value = true
        viewModelScope.launch {
            val result = exporter.exportTo(uri)
            exporting.value = false
            events.send(result.fold({ SettingsEvent.Exported(it) }, { SettingsEvent.ExportFailed }))
        }
    }

    fun unregister(onDone: () -> Unit) {
        viewModelScope.launch {
            registrar.unregister()
            onDone()
        }
    }
}

data class RetentionUiState(val policy: RetentionPolicy? = null, val usage: AudioUsage? = null)

class RetentionViewModel(private val settings: SettingsRepository, timeline: TimelineRepository) : ViewModel() {
    val state: StateFlow<RetentionUiState> = combine(settings.retention, timeline.audioUsage()) { p, u -> RetentionUiState(p, u) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RetentionUiState())

    fun setPolicy(policy: RetentionPolicy) {
        viewModelScope.launch { settings.setRetention(policy) }
    }
}
