package dev.owl.app.ui.onboarding

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val registeredName: String? = null,
    val firmwareVersion: String? = null,
    val fakeDeviceAvailable: Boolean = false,
    val busy: Boolean = false,
    /**
     * Why the last registration failed (e.g. the pendant refused pairing: "껐다 켠 뒤 2분 안에…").
     * Stays in the card until the next attempt; a snackbar went away while the user was off
     * power-cycling the pendant (U5 in docs/qa, decisions #47).
     */
    val registerError: String? = null,
)

sealed interface OnboardingEvent {
    /** Show the CompanionDeviceManager chooser. */
    data class LaunchChooser(val sender: IntentSender) : OnboardingEvent
}

/** A1 registration through [DeviceRegistrar]; the permission checklist lives in the screen (needs an Activity). */
class OnboardingViewModel(
    private val registrar: DeviceRegistrar,
    private val settings: SettingsRepository,
    controller: DeviceController,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val registerError = MutableStateFlow<String?>(null)
    private val events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val eventFlow: Flow<OnboardingEvent> = events.receiveAsFlow()

    val state: StateFlow<OnboardingUiState> = combine(registrar.registeredName, controller.state, busy, registerError) { name, device, b, error ->
        OnboardingUiState(
            registeredName = name,
            firmwareVersion = device.firmwareVersion.takeIf { name != null && device.name == name },
            fakeDeviceAvailable = registrar.fakeDeviceAvailable,
            busy = b,
            registerError = error.takeIf { name == null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(fakeDeviceAvailable = registrar.fakeDeviceAvailable))

    fun startAssociation() = launchOnce {
        registerError.value = null
        registrar.startAssociation().fold(
            onSuccess = { sender -> if (sender != null) events.send(OnboardingEvent.LaunchChooser(sender)) },
            onFailure = ::failed,
        )
    }

    fun onAssociationResult(data: Intent?) = launchOnce {
        registrar.onAssociationResult(data).onFailure(::failed)
    }

    fun registerFakeDevice() = launchOnce {
        registerError.value = null
        registrar.registerFakeDevice().onFailure(::failed)
    }

    private fun failed(e: Throwable) {
        registerError.value = e.message ?: GENERIC_FAILURE
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingDone(true)
            onDone()
        }
    }

    private fun launchOnce(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }
}

/** Marker for "failed without a reason"; the screen shows its generic string resource instead. */
internal const val GENERIC_FAILURE = ""
