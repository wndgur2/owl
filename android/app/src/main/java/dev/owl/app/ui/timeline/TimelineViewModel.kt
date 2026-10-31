package dev.owl.app.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.TimelineItem
import dev.owl.app.model.TimelineRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

data class TimelineUiState(
    /** Null until the first page arrives. */
    val items: List<TimelineItem>? = null,
    val rows: List<TimelineRow> = emptyList(),
    val bookmarkedOnly: Boolean = false,
    /** The repository returned a full page, so older records may exist. */
    val canLoadMore: Boolean = false,
    /** Date picked in the calendar; the screen scrolls there once it is loaded. */
    val jumpTarget: LocalDate? = null,
)

private const val PAGE = 50

/** Reverse infinite scroll (A7): the newest [limit] items are loaded; scrolling up raises the limit. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(private val repository: TimelineRepository) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)
    private val bookmarkedOnly = MutableStateFlow(false)
    private val jumpTarget = MutableStateFlow<LocalDate?>(null)

    private data class Query(val limit: Int, val bookmarkedOnly: Boolean)

    private val page = combine(limit, bookmarkedOnly, ::Query).flatMapLatest { q ->
        repository.timeline(q.limit, q.bookmarkedOnly).map { items ->
            TimelineUiState(
                items = items,
                rows = buildTimelineRows(items),
                bookmarkedOnly = q.bookmarkedOnly,
                canLoadMore = items.size >= q.limit,
            )
        }
    }

    val state: StateFlow<TimelineUiState> = combine(page, jumpTarget) { p, jump -> p.copy(jumpTarget = jump) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    fun setBookmarkedOnly(on: Boolean) {
        if (bookmarkedOnly.value == on) return
        limit.value = PAGE
        bookmarkedOnly.value = on
    }

    /** Called when the top of the list comes near; no-op if the current page is not full yet. */
    fun loadOlder(step: Int = PAGE) {
        val s = state.value
        if (!s.canLoadMore) return
        val loaded = s.items?.size ?: return
        limit.update { if (loaded >= it) it + step else it }
    }

    fun jumpTo(date: LocalDate) {
        jumpTarget.value = date
    }

    fun jumpHandled() {
        jumpTarget.value = null
    }
}
