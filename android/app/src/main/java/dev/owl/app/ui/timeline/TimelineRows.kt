package dev.owl.app.ui.timeline

import dev.owl.app.data.PLACE_RADIUS_M
import dev.owl.app.data.PlaceResolver
import dev.owl.app.model.DeviceNotice
import dev.owl.app.model.Place
import dev.owl.app.model.SegmentState
import dev.owl.app.model.StandaloneBookmark
import dev.owl.app.model.TimelineItem
import dev.owl.app.ui.components.toLocalDate
import java.time.LocalDate

/**
 * Flattened timeline in display order (oldest → newest, top → bottom):
 * a day divider, then place groups, each a [PlaceHeader] followed by its [Item]s and [Bookmark]s.
 * Every row carries the [date] it belongs to so the header can follow the scroll position.
 * `date == null` means the "시각 알 수 없음" group.
 */
sealed interface TimelineRow {
    val key: String
    val date: LocalDate?

    data class Day(override val date: LocalDate?, override val key: String) : TimelineRow

    data class PlaceHeader(
        val place: Place?,
        override val date: LocalDate?,
        /** Null for the unknown-time group. */
        val startMs: Long?,
        val endMs: Long?,
        /** Newest group of today that is still going on → "– 지금". */
        val ongoing: Boolean,
        override val key: String,
    ) : TimelineRow

    data class Item(val item: TimelineItem, override val date: LocalDate?) : TimelineRow {
        override val key: String get() = "item-${item.id}"
    }

    /** A bookmark pressed while nothing was recording (A9); not tappable. */
    data class Bookmark(val bookmark: StandaloneBookmark, override val date: LocalDate?) : TimelineRow {
        override val key: String get() = "bm-${bookmark.id}"
    }

    /** Recording off, storage full, … (decisions #46); not tappable. */
    data class Notice(val notice: DeviceNotice, override val date: LocalDate?) : TimelineRow {
        override val key: String get() = "ev-${notice.id}"
    }
}

/** One thing on the timeline before grouping: an entry or a standalone bookmark. */
sealed interface TimelineEntry {
    data class Entry(val item: TimelineItem) : TimelineEntry
    data class Mark(val bookmark: StandaloneBookmark) : TimelineEntry
    data class Notice(val notice: DeviceNotice) : TimelineEntry
}

/**
 * Merges standalone bookmarks and device notices (any order) into the entries (oldest first) by
 * time. Entries without a known start keep their place in the repository order and never pull
 * marks in front of them; a mark goes right before the first timed entry that starts after it.
 */
fun mergeTimeline(
    items: List<TimelineItem>,
    bookmarks: List<StandaloneBookmark>,
    notices: List<DeviceNotice> = emptyList(),
): List<TimelineEntry> {
    val out = ArrayList<TimelineEntry>(items.size + bookmarks.size + notices.size)
    val marks = (bookmarks.map { it.sortTimeMs to TimelineEntry.Mark(it) } + notices.map { it.sortTimeMs to TimelineEntry.Notice(it) })
        .sortedBy { it.first } // stable: equal sort times keep bookmarks-then-notices, each in query order
    var next = 0
    for (item in items) {
        val start = item.startUnixMs
        if (start != null) {
            while (next < marks.size && marks[next].first < start) out += marks[next++].second
        }
        out += TimelineEntry.Entry(item)
    }
    while (next < marks.size) out += marks[next++].second
    return out
}

/** A group counts as "지금" when its last recording ended less than this long ago. */
private const val ONGOING_WINDOW_MS = 10 * 60_000L

/**
 * Which place group a row belongs to (A7, decisions #46): same user-given name; for unnamed
 * places, still within [PLACE_RADIUS_M] of where the group started (so moving from the lecture
 * hall to the café starts a new "알 수 없는 장소" group); no GPS fix at all is its own
 * "위치 없음" group.
 */
private sealed interface PlaceGroup {
    data class Named(val name: String) : PlaceGroup
    data class Unnamed(val latitude: Double, val longitude: Double) : PlaceGroup
    data object NoFix : PlaceGroup
}

private fun groupOf(place: Place?): PlaceGroup = when {
    place == null -> PlaceGroup.NoFix
    place.name != null -> PlaceGroup.Named(place.name)
    else -> PlaceGroup.Unnamed(place.latitude, place.longitude)
}

private fun sameGroup(current: PlaceGroup?, next: PlaceGroup): Boolean = when {
    current is PlaceGroup.Unnamed && next is PlaceGroup.Unnamed ->
        PlaceResolver.distanceM(current.latitude, current.longitude, next.latitude, next.longitude) <= PLACE_RADIUS_M
    else -> current == next
}

fun buildTimelineRows(items: List<TimelineItem>, nowMs: Long = System.currentTimeMillis()): List<TimelineRow> =
    buildTimelineRows(items.map { TimelineEntry.Entry(it) }, nowMs)

@JvmName("buildTimelineRowsFromEntries")
fun buildTimelineRows(entries: List<TimelineEntry>, nowMs: Long = System.currentTimeMillis()): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>(entries.size * 2)
    var currentDay: LocalDate? = null
    var dayOpen = false
    var group: PlaceGroup? = null
    var headerIndex = -1
    val daysSeen = HashSet<LocalDate>()

    for (entry in entries) {
        val start: Long?
        val end: Long?
        val place: Place?
        val id: String
        when (entry) {
            is TimelineEntry.Entry -> {
                start = entry.item.startUnixMs
                end = entry.item.endUnixMs
                place = entry.item.place
                id = "${entry.item.id}"
            }
            is TimelineEntry.Mark -> {
                start = entry.bookmark.timeMs
                end = start
                place = entry.bookmark.place
                id = "bm-${entry.bookmark.id}"
            }
            is TimelineEntry.Notice -> {
                start = entry.notice.timeMs
                end = start
                place = null
                id = "ev-${entry.notice.id}"
            }
        }
        val date = start?.toLocalDate()
        if (!dayOpen || date != currentDay) {
            // A date can come back after the "시각 알 수 없음" group (recordings of a boot that never
            // synced sit between that day's earlier and later records): keys must stay unique.
            val key = when {
                date == null -> "day-unknown-$id"
                daysSeen.add(date) -> "day-$date"
                else -> "day-$date-$id"
            }
            rows += TimelineRow.Day(date, key)
            currentDay = date
            dayOpen = true
            group = null
        }
        // Notices have no place, and a bookmark without a GPS fix is not worth a "위치 없음"
        // header of its own: both just join the group they fall in.
        val joinsCurrent = entry is TimelineEntry.Notice || (entry is TimelineEntry.Mark && place == null)
        val next = groupOf(place)
        if (!joinsCurrent && (group == null || !sameGroup(group, next))) {
            rows += TimelineRow.PlaceHeader(
                place = place, date = date, startMs = start, endMs = end, ongoing = false, key = "place-$id",
            )
            headerIndex = rows.lastIndex
            group = next
        } else if (!joinsCurrent && start != null && end != null) {
            val header = rows[headerIndex] as TimelineRow.PlaceHeader
            rows[headerIndex] = header.copy(startMs = header.startMs ?: start, endMs = maxOf(header.endMs ?: end, end))
        }
        rows += when (entry) {
            is TimelineEntry.Entry -> TimelineRow.Item(entry.item, date)
            is TimelineEntry.Mark -> TimelineRow.Bookmark(entry.bookmark, date)
            is TimelineEntry.Notice -> TimelineRow.Notice(entry.notice, date)
        }
    }

    // "– 지금": only the very last group, only if it is today and still active.
    val lastHeader = rows.indexOfLast { it is TimelineRow.PlaceHeader }
    if (lastHeader >= 0) {
        val header = rows[lastHeader] as TimelineRow.PlaceHeader
        val groupItems = rows.subList(lastHeader + 1, rows.size).filterIsInstance<TimelineRow.Item>()
        val receiving = groupItems.any { it.item.state == SegmentState.RECEIVING }
        val end = header.endMs
        val isToday = header.date == nowMs.toLocalDate()
        if (groupItems.isNotEmpty() && isToday && end != null && (receiving || nowMs - end < ONGOING_WINDOW_MS)) {
            rows[lastHeader] = header.copy(ongoing = true)
        }
    }
    return rows
}
