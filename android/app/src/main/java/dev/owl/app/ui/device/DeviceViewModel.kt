package dev.owl.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DeviceViewModel(private val controller: DeviceController) : ViewModel() {
    val state: StateFlow<DeviceUiState> = controller.state

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
