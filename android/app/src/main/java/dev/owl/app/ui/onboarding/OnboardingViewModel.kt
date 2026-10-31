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
)

sealed interface OnboardingEvent {
    /** Show the CompanionDeviceManager chooser. */
    data class LaunchChooser(val sender: IntentSender) : OnboardingEvent
    /** [message]: the registrar's user-facing reason (e.g. the pendant refused pairing), if any. */
    data class RegistrationFailed(val message: String? = null) : OnboardingEvent
}

/** A1 registration through [DeviceRegistrar]; the permission checklist lives in the screen (needs an Activity). */
class OnboardingViewModel(
    private val registrar: DeviceRegistrar,
    private val settings: SettingsRepository,
    controller: DeviceController,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val eventFlow: Flow<OnboardingEvent> = events.receiveAsFlow()

    val state: StateFlow<OnboardingUiState> = combine(registrar.registeredName, controller.state, busy) { name, device, b ->
        OnboardingUiState(
            registeredName = name,
            firmwareVersion = device.firmwareVersion.takeIf { name != null && device.name == name },
            fakeDeviceAvailable = registrar.fakeDeviceAvailable,
            busy = b,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(fakeDeviceAvailable = registrar.fakeDeviceAvailable))

    fun startAssociation() = launchOnce {
        registrar.startAssociation().fold(
            onSuccess = { sender -> if (sender != null) events.send(OnboardingEvent.LaunchChooser(sender)) },
            onFailure = { events.send(OnboardingEvent.RegistrationFailed(it.message)) },
        )
    }

    fun onAssociationResult(data: Intent?) = launchOnce {
        registrar.onAssociationResult(data).onFailure { events.send(OnboardingEvent.RegistrationFailed(it.message)) }
    }

    fun registerFakeDevice() = launchOnce {
        registrar.registerFakeDevice().onFailure { events.send(OnboardingEvent.RegistrationFailed(it.message)) }
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
