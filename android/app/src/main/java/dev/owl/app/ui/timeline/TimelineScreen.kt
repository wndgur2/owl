package dev.owl.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.SampleData
import dev.owl.app.model.SegmentState
import dev.owl.app.model.TimelineItem
import dev.owl.app.ui.components.OwlFormats
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.components.today
import dev.owl.app.ui.theme.OwlTheme
import dev.owl.app.ui.theme.disabledContainer
import dev.owl.app.ui.theme.disabledContent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TimelineScreen(viewModel: TimelineViewModel, onOpenEntry: (Long) -> Unit, onOpenPlaces: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TimelineContent(
        state = state,
        onBookmarkedOnly = viewModel::setBookmarkedOnly,
        onLoadOlder = viewModel::loadOlder,
        onJumpTo = viewModel::jumpTo,
        onJumpHandled = viewModel::jumpHandled,
        onOpenEntry = onOpenEntry,
        onOpenPlaces = onOpenPlaces,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineContent(
    state: TimelineUiState,
    onBookmarkedOnly: (Boolean) -> Unit,
    onLoadOlder: (step: Int) -> Unit,
    onJumpTo: (LocalDate) -> Unit,
    onJumpHandled: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onOpenPlaces: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val formats = rememberOwlFormats()
    val snackbar = remember { SnackbarHostState() }
    // reverseLayout: index 0 is the newest row at the bottom, so the list opens at the bottom (A7).
    val display = remember(state.rows) { state.rows.asReversed() }
    val unknownTime = stringResource(R.string.time_unknown)

    val headerDate by remember(display) {
        derivedStateOf {
            val top = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index }
            val row = top?.let { display.getOrNull(it) }
            when {
                row == null -> formats.dayLong(today())
                row.date == null -> unknownTime
                else -> formats.dayLong(row.date!!)
            }
        }
    }
    val headerLocalDate by remember(display) {
        derivedStateOf {
            val top = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index }
            top?.let { display.getOrNull(it)?.date } ?: today()
        }
    }

    // Scrolling near the top loads older records.
    LaunchedEffect(listState, display.size, state.canLoadMore) {
        if (!state.canLoadMore) return@LaunchedEffect
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0) >= display.size - 8 }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadOlder(50) }
    }

    // Stay pinned to the bottom when a new recording arrives and the user is already there.
    val newestKey = display.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    val movedToNearest = stringResource(R.string.timeline_jump_nearest)
    LaunchedEffect(state.jumpTarget, state.rows) {
        val target = state.jumpTarget ?: return@LaunchedEffect
        val rows = state.rows
        val oldest = rows.firstNotNullOfOrNull { it.date }
        if (state.canLoadMore && (oldest == null || oldest > target)) {
            onLoadOlder(200) // keep raising the limit until the date is loaded
            return@LaunchedEffect
        }
        val hit = rows.withIndex().firstOrNull { (_, r) -> r is TimelineRow.Day && r.date != null && r.date >= target }
        if (hit == null) {
            listState.scrollToItem(0)
        } else {
            val reversed = rows.lastIndex - hit.index
            listState.scrollToItem(reversed)
            // In reverse layout the item lands at the bottom edge; push it up to the top edge. Not by
            // a whole viewport: near the oldest record the list can't scroll that far, so the item
            // already sits higher (offset > 0) and a full push would scroll the day out of view.
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == reversed }
            val viewport = info.viewportEndOffset - info.viewportStartOffset - info.beforeContentPadding - info.afterContentPadding
            val push = viewport - ((item?.offset ?: 0) + (item?.size ?: 0))
            if (push > 0) listState.scrollBy(-push.toFloat())
        }
        if (hit?.value?.date != target) snackbar.showSnackbar(movedToNearest)
        onJumpHandled()
    }

    var showPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    headerDate,
                    modifier = Modifier.weight(1f).semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = { showPicker = true }) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = stringResource(R.string.timeline_pick_date),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !state.bookmarkedOnly,
                    onClick = { onBookmarkedOnly(false) },
                    label = { Text(stringResource(R.string.filter_all)) },
                    leadingIcon = if (!state.bookmarkedOnly) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
                FilterChip(
                    selected = state.bookmarkedOnly,
                    onClick = { onBookmarkedOnly(true) },
                    label = { Text(stringResource(R.string.filter_bookmark)) },
                    leadingIcon = {
                        Icon(
                            if (state.bookmarkedOnly) Icons.Filled.Check else Icons.Outlined.BookmarkBorder,
                            null,
                            Modifier.size(18.dp),
                        )
                    },
                )
                FilterChip(
                    selected = false,
                    onClick = onOpenPlaces,
                    label = { Text(stringResource(R.string.filter_by_place)) },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val items = state.items
                when {
                    items == null -> Unit
                    items.isEmpty() && state.bookmarks.isEmpty() -> dev.owl.app.ui.components.EmptyState(
                        icon = if (state.bookmarkedOnly) Icons.Outlined.BookmarkBorder else Icons.Outlined.GraphicEq,
                        title = stringResource(if (state.bookmarkedOnly) R.string.timeline_empty_bookmarks else R.string.timeline_empty),
                        body = stringResource(
                            if (state.bookmarkedOnly) R.string.timeline_empty_bookmarks_body else R.string.timeline_empty_body,
                        ),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                    ) {
                        items(display, key = { it.key }, contentType = { it::class.simpleName }) { row ->
                            when (row) {
                                is TimelineRow.Day -> DayDivider(row.date, formats)
                                is TimelineRow.PlaceHeader -> PlaceHeaderRow(row, formats)
                                is TimelineRow.Item -> TimelineCard(row.item, formats, onClick = { onOpenEntry(row.item.id) })
                                is TimelineRow.Bookmark -> StandaloneBookmarkRow(row.bookmark.timeMs, formats)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val todayDate = today()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = headerLocalDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(todayDate) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(todayDate)

                    override fun isSelectableYear(year: Int): Boolean = year <= todayDate.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            onJumpTo(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.action_move)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(pickerState)
        }
    }
}

@Composable
private fun DayDivider(date: LocalDate?, formats: OwlFormats) {
    val label = when (date) {
        null -> stringResource(R.string.time_unknown)
        today() -> stringResource(R.string.today)
        else -> formats.dayLong(date)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            label,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PlaceHeaderRow(row: TimelineRow.PlaceHeader, formats: OwlFormats) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            formats.place(row.place),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val start = row.startMs
        if (start != null) {
            val end = if (row.ongoing) stringResource(R.string.now) else formats.hm(row.endMs ?: start)
            Text(
                stringResource(R.string.time_range, formats.hm(start), end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A bookmark pressed while nothing was recording (A9). Low emphasis, same look as the bookmark
 * marker inside an entry; there is no recording to open, so it is not clickable.
 */
@Composable
fun StandaloneBookmarkRow(timeMs: Long?, formats: OwlFormats, modifier: Modifier = Modifier) {
    val time = if (timeMs != null) formats.hm(timeMs) else stringResource(R.string.time_unknown)
    val description = stringResource(R.string.standalone_bookmark_description, time)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Bookmark, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(
            stringResource(R.string.standalone_bookmark, time),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun BookmarkBadge(count: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(Icons.Filled.Bookmark, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(
            if (count > 1) stringResource(R.string.bookmark_count, count) else stringResource(R.string.bookmark),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun TimelineCard(item: TimelineItem, formats: OwlFormats, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val receiving = item.state == SegmentState.RECEIVING
    val start = item.startUnixMs
    Surface(
        onClick = onClick,
        enabled = !receiving,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerLow,
        contentColor = if (receiving) colors.disabledContent else colors.onSurface,
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (start != null) formats.hm(start) else stringResource(R.string.time_unknown),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (receiving) colors.disabledContent else colors.primary,
                )
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        formats.duration(item.durationMs, item.durationIsEstimate),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (receiving) colors.disabledContent else colors.onSurfaceVariant,
                    )
                    if (item.bookmarkCount > 0 && !receiving) BookmarkBadge(item.bookmarkCount)
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .background(if (receiving) colors.disabledContainer else colors.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (receiving) colors.disabledContent else colors.onSecondaryContainer,
                    )
                }
            }
            when {
                receiving -> {
                    Text(
                        stringResource(R.string.state_receiving),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    val progress = item.receiveProgress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                }
                item.preview != null -> Text(
                    item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.state != SegmentState.DONE -> TranscribingLabel()
                else -> Text(
                    stringResource(R.string.state_no_speech),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TranscribingLabel(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
        )
        Text(
            stringResource(R.string.state_transcribing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, heightDp = 844, widthDp = 390)
@Preview(showBackground = true, heightDp = 844, widthDp = 390, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TimelinePreview() {
    OwlTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            TimelineContent(
                state = TimelineUiState(
                    items = SampleData.timeline,
                    bookmarks = SampleData.standaloneBookmarks,
                    rows = buildTimelineRows(mergeTimeline(SampleData.timeline, SampleData.standaloneBookmarks)),
                ),
                onBookmarkedOnly = {}, onLoadOlder = {}, onJumpTo = {}, onJumpHandled = {}, onOpenEntry = {}, onOpenPlaces = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 400)
@Composable
private fun TimelineEmptyPreview() {
    OwlTheme {
        TimelineContent(
            state = TimelineUiState(items = emptyList()),
            onBookmarkedOnly = {}, onLoadOlder = {}, onJumpTo = {}, onJumpHandled = {}, onOpenEntry = {}, onOpenPlaces = {},
        )
    }
}
