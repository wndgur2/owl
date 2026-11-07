package dev.owl.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.AppUsageRepository
import dev.owl.app.model.AppUsageSummary
import dev.owl.app.model.PlaceSummary
import dev.owl.app.model.PlacesRepository
import dev.owl.app.ui.components.today
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** How many days the 집계 tab adds up, ending at the chosen day. */
enum class StatsSpan(val days: Long) { DAY(1), WEEK(7), MONTH(30) }

data class StatsPeriod(val span: StatsSpan, val end: LocalDate) {
    val start: LocalDate get() = end.minusDays(span.days - 1)
}

data class StatsUiState(
    val period: StatsPeriod,
    /** The period ends today, so there is nothing newer to go to. */
    val isLatest: Boolean = true,
    /** Null until this period is loaded. */
    val places: List<PlaceSummary>? = null,
    val apps: List<AppUsageSummary>? = null,
    val appUsageEnabled: Boolean = false,
    val hasAppUsage: Boolean = false,
)

/** The 집계 tab (A19): time at each place and the phone's app use (A18) over a period. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val usage: AppUsageRepository,
    placesRepository: PlacesRepository,
    private val currentDay: () -> LocalDate = ::today,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod(StatsSpan.DAY, currentDay()))
    private var lastShownDay = period.value.end

    private fun millis(date: LocalDate) = date.atStartOfDay(zone()).toInstant().toEpochMilli()
    private fun fromMs(p: StatsPeriod) = millis(p.start)
    private fun toMs(p: StatsPeriod) = millis(p.end.plusDays(1))

    private val places = period.flatMapLatest { p -> placesRepository.stays(fromMs(p), toMs(p)).map { p to it } }
    private val apps = period.flatMapLatest { p -> usage.summary(fromMs(p), toMs(p)).map { p to it } }

    val state: StateFlow<StatsUiState> = combine(period, places, apps, usage.enabled, usage.hasRecords) { p, placeRows, appRows, enabled, hasRecords ->
        StatsUiState(
            period = p,
            isLatest = p.end >= currentDay(),
            places = placeRows.second.takeIf { placeRows.first == p },
            apps = appRows.second.takeIf { appRows.first == p },
            appUsageEnabled = enabled,
            hasAppUsage = hasRecords,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(period.value))

    fun hasUsageAccess(): Boolean = usage.hasAccess()

    /** This ViewModel lives for days: someone looking at today keeps looking at today. */
    fun onScreenShown() {
        val day = currentDay()
        if (period.value.end == lastShownDay) period.value = period.value.copy(end = day)
        lastShownDay = day
    }

    fun setSpan(span: StatsSpan) {
        period.value = period.value.copy(span = span)
    }

    fun previous() {
        val p = period.value
        period.value = p.copy(end = p.end.minusDays(p.span.days))
    }

    fun next() {
        val p = period.value
        val day = currentDay()
        val end = p.end.plusDays(p.span.days)
        period.value = p.copy(end = if (end > day) day else end)
    }

    fun setAppUsageEnabled(on: Boolean) {
        viewModelScope.launch { usage.setEnabled(on) }
    }

    fun deleteAppUsage() {
        viewModelScope.launch { usage.deleteAll() }
    }
}
