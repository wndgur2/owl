package dev.owl.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.SearchFilter
import dev.owl.app.model.SearchHit
import dev.owl.app.model.SearchPeriod
import dev.owl.app.model.StandaloneBookmark
import dev.owl.app.model.TimelineRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Results together with the filter that produced them, so highlighting matches the shown hits. */
data class SearchResults(
    val filter: SearchFilter,
    val hits: List<SearchHit>,
    /** Standalone bookmarks (A9); only for a blank query with "북마크만" on. */
    val bookmarks: List<StandaloneBookmark> = emptyList(),
) {
    val isEmpty: Boolean get() = hits.isEmpty() && bookmarks.isEmpty()
}

data class SearchUiState(
    val filter: SearchFilter = SearchFilter(),
    /** Null while idle (nothing typed, no filter) or before the first answer. */
    val results: SearchResults? = null,
    val placeNames: List<String> = emptyList(),
) {
    val idle: Boolean get() = filter.isIdle()
}

fun SearchFilter.isIdle(): Boolean =
    query.isBlank() && period == SearchPeriod.ALL && placeName == null && !bookmarkedOnly && !preservedOnly

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: TimelineRepository) : ViewModel() {
    private val filter = MutableStateFlow(SearchFilter())

    private val results = filter
        .debounce { if (it.query.isNotBlank()) 250L else 0L }
        .flatMapLatest { f ->
            val trimmed = f.copy(query = f.query.trim())
            when {
                f.isIdle() -> flowOf(null)
                // Filter-only bookmark search also lists bookmarks pressed outside any recording.
                trimmed.query.isEmpty() && f.bookmarkedOnly -> combine(
                    repository.search(trimmed),
                    repository.searchBookmarks(trimmed),
                ) { hits, marks -> SearchResults(f, hits, marks) }
                else -> repository.search(trimmed).map { SearchResults(f, it) }
            }
        }

    val state: StateFlow<SearchUiState> = combine(filter, results, repository.placeNames()) { f, r, places ->
        SearchUiState(filter = f, results = r, placeNames = places)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(query: String) = filter.update { it.copy(query = query) }
    fun setPeriod(period: SearchPeriod) = filter.update { it.copy(period = period) }
    fun setPlace(name: String?) = filter.update { it.copy(placeName = name) }
    fun setBookmarkedOnly(on: Boolean) = filter.update { it.copy(bookmarkedOnly = on) }
    fun setPreservedOnly(on: Boolean) = filter.update { it.copy(preservedOnly = on) }
}
