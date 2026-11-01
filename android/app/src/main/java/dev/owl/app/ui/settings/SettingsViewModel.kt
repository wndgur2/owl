package dev.owl.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.AudioUsage
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.Exporter
import dev.owl.app.model.PlacesRepository
import dev.owl.app.model.RetentionController
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.ThemeMode
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
    /** 등록 해제 in progress (UNPAIR waits up to 15 s for a pendant that is away). */
    val unregistering: Boolean = false,
    /** Named places (A5/A7), null until known. */
    val namedPlaceCount: Int? = null,
    /** A17, null until loaded. */
    val themeMode: ThemeMode? = null,
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
    places: PlacesRepository,
) : ViewModel() {
    private val exporting = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val unregistering = kotlinx.coroutines.flow.MutableStateFlow(false)
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
    }.combine(places.places()) { s, list -> s.copy(namedPlaceCount = list.size) }
        .combine(settings.themeMode) { s, mode -> s.copy(themeMode = mode) }
        .combine(unregistering) { s, u -> s.copy(unregistering = u) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(fakeDeviceAvailable = registrar.fakeDeviceAvailable))

    fun setLocationEnabled(on: Boolean) {
        viewModelScope.launch { settings.setLocationEnabled(on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
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
        if (unregistering.value) return
        unregistering.value = true
        viewModelScope.launch {
            try {
                registrar.unregister()
            } finally {
                unregistering.value = false
            }
            onDone()
        }
    }
}

/** A period that would delete audio right now, waiting for the user's yes (decisions #48). */
data class PendingRetention(val policy: RetentionPolicy, val entries: Int)

data class RetentionUiState(
    val policy: RetentionPolicy? = null,
    val usage: AudioUsage? = null,
    val pending: PendingRetention? = null,
)

class RetentionViewModel(
    settings: SettingsRepository,
    timeline: TimelineRepository,
    private val retention: RetentionController,
) : ViewModel() {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<PendingRetention?>(null)

    val state: StateFlow<RetentionUiState> = combine(settings.retention, timeline.audioUsage(), pending) { p, u, w -> RetentionUiState(p, u, w) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RetentionUiState())

    /** Applies at once; if that deletes audio now, asks first (it cannot be undone). */
    fun setPolicy(policy: RetentionPolicy) {
        if (policy == state.value.policy) return
        viewModelScope.launch {
            val n = retention.deletableNow(policy)
            if (n > 0) pending.value = PendingRetention(policy, n) else retention.change(policy)
        }
    }

    fun confirm() {
        val p = pending.value ?: return
        pending.value = null
        viewModelScope.launch { retention.change(p.policy) }
    }

    fun cancel() {
        pending.value = null
    }
}
