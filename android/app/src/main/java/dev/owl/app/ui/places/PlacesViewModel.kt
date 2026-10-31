package dev.owl.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.NamedPlace
import dev.owl.app.model.PlacesRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Null until the repository answered once. */
data class PlacesUiState(val places: List<NamedPlace>? = null)

/** Settings › 장소: the places the user named (A5/A7). Names are made from Entry detail. */
class PlacesViewModel(private val repository: PlacesRepository) : ViewModel() {
    val state: StateFlow<PlacesUiState> = repository.places()
        .map { PlacesUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    private val failures = Channel<Unit>(Channel.CONFLATED)

    /** One event per failed rename/delete, for a snackbar. */
    val failureFlow: Flow<Unit> = failures.receiveAsFlow()

    fun rename(id: Long, name: String) = launchCatching { repository.renamePlace(id, name) }

    fun delete(id: Long) = launchCatching { repository.deletePlace(id) }

    private fun launchCatching(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures.send(Unit)
            }
        }
    }
}
