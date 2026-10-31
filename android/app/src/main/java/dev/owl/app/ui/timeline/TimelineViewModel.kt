package dev.owl.app.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.StandaloneBookmark
import dev.owl.app.model.TimelineItem
import dev.owl.app.model.TimelineRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TimelineUiState(
    /** Null until the first page arrives. */
    val items: List<TimelineItem>? = null,
    /** Standalone bookmarks (A9) within the loaded range, oldest first. */
    val bookmarks: List<StandaloneBookmark> = emptyList(),
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

    private val page = combine(limit, bookmarkedOnly, ::Query).flatMapLatest(::pageFor)

    /**
     * Entries, plus the standalone bookmarks from the oldest loaded entry on (all of them once
     * everything is loaded). Bookmarks show in both filter modes: they are bookmarks themselves.
     * The bookmark query is only redone when that start time changes, not on every entry update
     * (receive progress ticks often).
     */
    private fun pageFor(q: Query): Flow<TimelineUiState> = channelFlow {
        val items = MutableStateFlow<List<TimelineItem>?>(null)
        launch { repository.timeline(q.limit, q.bookmarkedOnly).collect { items.value = it } }
        val loaded = items.filterNotNull()
        val bookmarks = loaded
            .map { list -> if (list.size >= q.limit) list.firstNotNullOfOrNull { it.startUnixMs } else null }
            .distinctUntilChanged()
            .flatMapLatest { since -> repository.standaloneBookmarks(since) }
        combine(loaded, bookmarks) { list, marks ->
            TimelineUiState(
                items = list,
                bookmarks = marks,
                rows = buildTimelineRows(mergeTimeline(list, marks)),
                bookmarkedOnly = q.bookmarkedOnly,
                canLoadMore = list.size >= q.limit,
            )
        }.collect { send(it) }
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
