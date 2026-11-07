package dev.owl.app.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.AppUsageRepository
import dev.owl.app.model.AppUsageSummary
import dev.owl.app.ui.components.today
import dev.owl.app.usage.UsageSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UsageUiState(
    val enabled: Boolean = false,
    val date: LocalDate,
    /** Null until the chosen day is loaded. */
    val apps: List<AppUsageSummary>? = null,
    val hasRecords: Boolean = false,
    val isToday: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(
    private val usage: AppUsageRepository,
    private val currentDay: () -> LocalDate = ::today,
) : ViewModel() {
    private val date = MutableStateFlow(currentDay())
    private val apps = date.flatMapLatest { d -> usage.day(d).map { d to it } }
    private var syncing: Job? = null

    val state: StateFlow<UsageUiState> = combine(usage.enabled, usage.hasRecords, date, apps) { enabled, hasRecords, d, loaded ->
        UsageUiState(
            enabled = enabled,
            date = d,
            apps = loaded.second.takeIf { loaded.first == d },
            hasRecords = hasRecords,
            isToday = d >= currentDay(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState(date = date.value))

    fun hasAccess(): Boolean = usage.hasAccess()

    /**
     * Coming back to this screen. The app just left only counts as finished once the same-app merge
     * window has passed, so read again after it.
     */
    fun onScreenShown() {
        syncing?.cancel()
        syncing = viewModelScope.launch {
            usage.sync()
            delay(UsageSessions.MERGE_GAP_MS + 500)
            usage.sync()
        }
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { usage.setEnabled(on) }
    }

    fun previousDay() {
        date.value = date.value.minusDays(1)
    }

    fun nextDay() {
        if (date.value < currentDay()) date.value = date.value.plusDays(1)
    }

    fun deleteAll() {
        viewModelScope.launch { usage.deleteAll() }
    }
}
