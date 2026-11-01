package dev.owl.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.DeviceUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DeviceViewModel(private val controller: DeviceController, private val registrar: DeviceRegistrar) : ViewModel() {
    val state: StateFlow<DeviceUiState> = controller.state

    private val reRegistering = MutableStateFlow(false)

    /** True while "다시 등록" unregisters (UNPAIR can take up to 15 s when the pendant is away). */
    val reRegisteringState: StateFlow<Boolean> = reRegistering

    /**
     * "다시 등록" (decisions #47): the pendant no longer knows this phone. Forget it here (same as
     * 설정 › 등록 해제), then [onDone] opens registration, which starts the chooser right away.
     */
    fun reRegister(onDone: () -> Unit) {
        if (reRegistering.value) return
        reRegistering.value = true
        viewModelScope.launch {
            try {
                registrar.unregister()
            } finally {
                reRegistering.value = false
            }
            onDone()
        }
    }

    private val failures = Channel<Unit>(Channel.CONFLATED)

    /** Emits when SET_RECORDING failed, for the error snackbar. */
    val recordingFailed: Flow<Unit> = failures.receiveAsFlow()

    fun syncNow() = controller.syncNow()

    fun reconnect() = controller.reconnect()

    fun setRecording(on: Boolean) {
        viewModelScope.launch {
            if (controller.setRecording(on).isFailure) failures.send(Unit)
        }
    }
}
