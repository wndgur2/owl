package dev.owl.app.ui.timeline

import dev.owl.app.model.Place
import dev.owl.app.model.SegmentState
import dev.owl.app.model.TimelineItem
import dev.owl.app.ui.components.toLocalDate
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Flattened timeline in display order (oldest → newest, top → bottom):
 * a day divider, then place groups, each a [PlaceHeader] followed by its [Item]s.
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
}

/** A group counts as "지금" when its last recording ended less than this long ago. */
private const val ONGOING_WINDOW_MS = 10 * 60_000L

/** Same place = same name, or (unnamed) the same coordinates to ~10 m. */
private fun placeKey(place: Place?): String = when {
    place == null -> "none"
    place.name != null -> "n:${place.name}"
    else -> "c:${(place.latitude * 10_000).roundToLong()},${(place.longitude * 10_000).roundToLong()}"
}

fun buildTimelineRows(items: List<TimelineItem>, nowMs: Long = System.currentTimeMillis()): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>(items.size * 2)
    var currentDay: LocalDate? = null
    var dayOpen = false
    var groupKey: String? = null
    var headerIndex = -1

    for (item in items) {
        val start = item.startUnixMs
        val date = start?.toLocalDate()
        if (!dayOpen || date != currentDay) {
            rows += TimelineRow.Day(date, if (date != null) "day-$date" else "day-unknown-${item.id}")
            currentDay = date
            dayOpen = true
            groupKey = null
        }
        val key = placeKey(item.place)
        if (key != groupKey) {
            rows += TimelineRow.PlaceHeader(
                place = item.place, date = date, startMs = start, endMs = start?.plus(item.durationMs),
                ongoing = false, key = "place-${item.id}",
            )
            headerIndex = rows.lastIndex
            groupKey = key
        } else if (start != null) {
            val header = rows[headerIndex] as TimelineRow.PlaceHeader
            rows[headerIndex] = header.copy(endMs = maxOf(header.endMs ?: start, start + item.durationMs))
        }
        rows += TimelineRow.Item(item, date)
    }

    // "– 지금": only the very last group, only if it is today and still active.
    val lastHeader = rows.indexOfLast { it is TimelineRow.PlaceHeader }
    if (lastHeader >= 0) {
        val header = rows[lastHeader] as TimelineRow.PlaceHeader
        val groupItems = rows.subList(lastHeader + 1, rows.size).filterIsInstance<TimelineRow.Item>()
        val receiving = groupItems.any { it.item.state == SegmentState.RECEIVING }
        val end = header.endMs
        val isToday = header.date == nowMs.toLocalDate()
        if (isToday && end != null && (receiving || nowMs - end < ONGOING_WINDOW_MS)) {
            rows[lastHeader] = header.copy(ongoing = true)
        }
    }
    return rows
}
