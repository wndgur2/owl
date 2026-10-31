package dev.owl.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.SampleData
import dev.owl.app.model.SearchFilter
import dev.owl.app.model.SearchHit
import dev.owl.app.model.SearchPeriod
import dev.owl.app.model.Sentence
import dev.owl.app.ui.components.EmptyState
import dev.owl.app.ui.components.OwlFormats
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.components.toLocalDate
import dev.owl.app.ui.components.today
import dev.owl.app.ui.theme.OwlTheme
import java.time.LocalDate

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    openPlaceMenu: Boolean,
    onPlaceMenuOpened: () -> Unit,
    onOpenEntry: (id: Long, atMs: Long?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        openPlaceMenu = openPlaceMenu,
        onPlaceMenuOpened = onPlaceMenuOpened,
        onQuery = viewModel::setQuery,
        onPeriod = viewModel::setPeriod,
        onPlace = viewModel::setPlace,
        onBookmarkedOnly = viewModel::setBookmarkedOnly,
        onPreservedOnly = viewModel::setPreservedOnly,
        onOpenEntry = onOpenEntry,
    )
}

@Composable
fun SearchContent(
    state: SearchUiState,
    openPlaceMenu: Boolean,
    onPlaceMenuOpened: () -> Unit,
    onQuery: (String) -> Unit,
    onPeriod: (SearchPeriod) -> Unit,
    onPlace: (String?) -> Unit,
    onBookmarkedOnly: (Boolean) -> Unit,
    onPreservedOnly: (Boolean) -> Unit,
    onOpenEntry: (id: Long, atMs: Long?) -> Unit,
) {
    val formats = rememberOwlFormats()
    val focus = LocalFocusManager.current
    val filter = state.filter
    // Local text so typing never waits for the ViewModel round trip.
    var text by rememberSaveable { mutableStateOf(filter.query) }
    var periodMenu by remember { mutableStateOf(false) }
    var placeMenu by remember { mutableStateOf(false) }

    LaunchedEffect(openPlaceMenu) {
        if (openPlaceMenu) {
            placeMenu = true
            onPlaceMenuOpened()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = text,
            onValueChange = {
                text = it
                onQuery(it)
            },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            text = ""
                            onQuery("")
                        },
                    ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear)) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                FilterChip(
                    selected = filter.period != SearchPeriod.ALL,
                    onClick = { periodMenu = true },
                    label = { Text(periodLabel(filter.period)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                    SearchPeriod.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(periodLabel(p)) },
                            trailingIcon = if (p == filter.period) {
                                { Icon(Icons.Filled.Check, null) }
                            } else {
                                null
                            },
                            onClick = {
                                onPeriod(p)
                                periodMenu = false
                            },
                        )
                    }
                }
            }
            Box {
                FilterChip(
                    selected = filter.placeName != null,
                    onClick = { placeMenu = true },
                    label = { Text(filter.placeName ?: stringResource(R.string.search_place)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = placeMenu, onDismissRequest = { placeMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_all_places)) },
                        trailingIcon = if (filter.placeName == null) {
                            { Icon(Icons.Filled.Check, null) }
                        } else {
                            null
                        },
                        onClick = {
                            onPlace(null)
                            placeMenu = false
                        },
                    )
                    if (state.placeNames.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_no_places)) }, onClick = {}, enabled = false)
                    }
                    state.placeNames.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            trailingIcon = if (filter.placeName == name) {
                                { Icon(Icons.Filled.Check, null) }
                            } else {
                                null
                            },
                            onClick = {
                                onPlace(name)
                                placeMenu = false
                            },
                        )
                    }
                }
            }
            FilterChip(
                selected = filter.bookmarkedOnly,
                onClick = { onBookmarkedOnly(!filter.bookmarkedOnly) },
                label = { Text(stringResource(R.string.search_bookmarked_only)) },
                leadingIcon = {
                    Icon(if (filter.bookmarkedOnly) Icons.Filled.Check else Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = filter.preservedOnly,
                onClick = { onPreservedOnly(!filter.preservedOnly) },
                label = { Text(stringResource(R.string.search_preserved_only)) },
                leadingIcon = {
                    Icon(if (filter.preservedOnly) Icons.Filled.Check else Icons.Outlined.Lock, null, Modifier.size(18.dp))
                },
            )
        }

        val results = state.results
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.idle -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.search_idle_title),
                    body = stringResource(R.string.search_idle_body),
                    modifier = Modifier.align(Alignment.Center),
                )
                results == null -> Unit
                results.hits.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_empty_title),
                    body = stringResource(R.string.search_empty_body),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> SearchResultList(results, formats, onOpenEntry)
            }
        }
    }
}

@Composable
private fun periodLabel(p: SearchPeriod): String = stringResource(
    when (p) {
        SearchPeriod.ALL -> R.string.period_all
        SearchPeriod.TODAY -> R.string.period_today
        SearchPeriod.LAST_7_DAYS -> R.string.period_7
        SearchPeriod.LAST_30_DAYS -> R.string.period_30
    },
)

private data class HitGroup(val date: LocalDate?, val hits: List<SearchHit>)

/** Consecutive hits on the same day share a header; order is the repository's. */
private fun groupByDay(hits: List<SearchHit>): List<HitGroup> {
    val out = ArrayList<HitGroup>()
    for (hit in hits) {
        val date = hit.item.startUnixMs?.toLocalDate()
        val last = out.lastOrNull()
        if (last != null && last.date == date) {
            out[out.lastIndex] = last.copy(hits = last.hits + hit)
        } else {
            out += HitGroup(date, listOf(hit))
        }
    }
    return out
}

@Composable
private fun SearchResultList(results: SearchResults, formats: OwlFormats, onOpenEntry: (Long, Long?) -> Unit) {
    val groups = remember(results.hits) { groupByDay(results.hits) }
    val count = remember(results.hits) { results.hits.map { it.item.id }.distinct().size }
    val query = results.filter.query.trim()
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Medium,
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "count") {
            Text(
                stringResource(R.string.search_count, count),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        groups.forEachIndexed { gi, group ->
            item(key = "h-$gi-${group.date}") {
                if (gi > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    when (group.date) {
                        null -> stringResource(R.string.time_unknown)
                        today() -> stringResource(R.string.search_today_header, formats.monthDay(group.date))
                        else -> formats.dayLong(group.date)
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(group.hits, key = { "r-${it.item.id}-${it.sentence?.index}" }) { hit ->
                SearchResultRow(hit, query, highlight, formats, onClick = { onOpenEntry(hit.item.id, hit.sentence?.startMs) })
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, query: String, highlight: SpanStyle, formats: OwlFormats, onClick: () -> Unit) {
    val item = hit.item
    val start = item.startUnixMs
    val time = if (start != null) formats.hm(start + (hit.sentence?.startMs ?: 0)) else stringResource(R.string.time_unknown)
    val meta = if (item.place != null) stringResource(R.string.meta_time_place, time, formats.place(item.place)) else time
    val body = hit.sentence?.text ?: item.preview.orEmpty()
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.bookmarkCount > 0) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = stringResource(R.string.bookmark),
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Text(
            highlighted(snippet(body, query), query, highlight),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Starts a little before the first match so it is visible within two lines. */
internal fun snippet(text: String, query: String, before: Int = 16): String {
    if (query.isEmpty()) return text
    val idx = text.indexOf(query, ignoreCase = true)
    if (idx <= before) return text
    var start = idx - before
    val space = text.lastIndexOf(' ', idx - 1)
    if (space in (start - 6) until idx) start = space + 1
    return "…" + text.substring(start)
}

private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var from = 0
    while (true) {
        val idx = text.indexOf(query, from, ignoreCase = true)
        if (idx < 0) break
        append(text.substring(from, idx))
        withStyle(style) { append(text.substring(idx, idx + query.length)) }
        from = idx + query.length
    }
    append(text.substring(from))
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SearchPreview() {
    val hits = SampleData.timeline.filter { it.preview?.contains("마감") == true || it.id == 1L }
        .map { SearchHit(it, Sentence(0, 63_000, 70_000, it.preview!!)) }
    OwlTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SearchContent(
                state = SearchUiState(
                    filter = SearchFilter(query = "마감"),
                    results = SearchResults(SearchFilter(query = "마감"), hits),
                    placeNames = listOf("중앙도서관", "집"),
                ),
                openPlaceMenu = false, onPlaceMenuOpened = {}, onQuery = {}, onPeriod = {}, onPlace = {},
                onBookmarkedOnly = {}, onPreservedOnly = {}, onOpenEntry = { _, _ -> },
            )
        }
    }
}
