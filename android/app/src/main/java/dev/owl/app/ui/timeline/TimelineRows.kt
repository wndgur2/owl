package dev.owl.app.ui.timeline

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
}

/** One thing on the timeline before grouping: an entry or a standalone bookmark. */
sealed interface TimelineEntry {
    data class Entry(val item: TimelineItem) : TimelineEntry
    data class Mark(val bookmark: StandaloneBookmark) : TimelineEntry
}

/**
 * Merges standalone bookmarks (oldest first) into the entries (oldest first) by time.
 * Entries without a known start keep their place in the repository order and never pull
 * bookmarks in front of them; a bookmark goes right before the first timed entry that starts
 * after it.
 */
fun mergeTimeline(items: List<TimelineItem>, bookmarks: List<StandaloneBookmark>): List<TimelineEntry> {
    val out = ArrayList<TimelineEntry>(items.size + bookmarks.size)
    val marks = bookmarks.sortedBy { it.sortTimeMs }
    var next = 0
    for (item in items) {
        val start = item.startUnixMs
        if (start != null) {
            while (next < marks.size && marks[next].sortTimeMs < start) out += TimelineEntry.Mark(marks[next++])
        }
        out += TimelineEntry.Entry(item)
    }
    while (next < marks.size) out += TimelineEntry.Mark(marks[next++])
    return out
}

/** A group counts as "지금" when its last recording ended less than this long ago. */
private const val ONGOING_WINDOW_MS = 10 * 60_000L

/**
 * Same place = same user-given name. Every unnamed place (and "no GPS fix") is shown as
 * "알 수 없는 장소", so they form one group rather than repeating that label.
 */
private fun placeKey(place: Place?): String = place?.name?.let { "n:$it" } ?: "unknown"

fun buildTimelineRows(items: List<TimelineItem>, nowMs: Long = System.currentTimeMillis()): List<TimelineRow> =
    buildTimelineRows(items.map { TimelineEntry.Entry(it) }, nowMs)

@JvmName("buildTimelineRowsFromEntries")
fun buildTimelineRows(entries: List<TimelineEntry>, nowMs: Long = System.currentTimeMillis()): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>(entries.size * 2)
    var currentDay: LocalDate? = null
    var dayOpen = false
    var groupKey: String? = null
    var headerIndex = -1

    for (entry in entries) {
        val start: Long?
        val end: Long?
        val place: Place?
        val id: String
        when (entry) {
            is TimelineEntry.Entry -> {
                start = entry.item.startUnixMs
                end = start?.plus(entry.item.durationMs)
                place = entry.item.place
                id = "${entry.item.id}"
            }
            is TimelineEntry.Mark -> {
                start = entry.bookmark.timeMs
                end = start
                place = entry.bookmark.place
                id = "bm-${entry.bookmark.id}"
            }
        }
        val date = start?.toLocalDate()
        if (!dayOpen || date != currentDay) {
            rows += TimelineRow.Day(date, if (date != null) "day-$date" else "day-unknown-$id")
            currentDay = date
            dayOpen = true
            groupKey = null
        }
        val key = placeKey(place)
        if (key != groupKey) {
            rows += TimelineRow.PlaceHeader(
                place = place, date = date, startMs = start, endMs = end, ongoing = false, key = "place-$id",
            )
            headerIndex = rows.lastIndex
            groupKey = key
        } else if (start != null && end != null) {
            val header = rows[headerIndex] as TimelineRow.PlaceHeader
            rows[headerIndex] = header.copy(startMs = header.startMs ?: start, endMs = maxOf(header.endMs ?: end, end))
        }
        rows += when (entry) {
            is TimelineEntry.Entry -> TimelineRow.Item(entry.item, date)
            is TimelineEntry.Mark -> TimelineRow.Bookmark(entry.bookmark, date)
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
