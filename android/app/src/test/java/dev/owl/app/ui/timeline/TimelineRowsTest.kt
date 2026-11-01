package dev.owl.app.ui.timeline

import dev.owl.app.model.DeviceNotice
import dev.owl.app.model.Place
import dev.owl.app.model.SegmentState
import dev.owl.app.model.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimelineRowsTest {
    private val home = Place("집", 37.58, 127.04, 1)

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 30, h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun item(id: Long, start: Long, durationMs: Long, end: Long?, place: Place? = home) = TimelineItem(
        id = id, startUnixMs = start, durationMs = durationMs, durationIsEstimate = false, place = place, preview = null,
        state = SegmentState.DONE, receiveProgress = null, bookmarkCount = 0, preserveAudio = false, audioDeleted = false,
        endUnixMs = end,
    )

    @Test
    fun placeHeaderEndsWhenTheLastSegmentEnds() {
        // A merged entry: 10:00–10:19 on the clock, but only 40 s of audio (VAD dropped the pauses).
        val rows = buildTimelineRows(listOf(item(1, at(10, 0), 40_000, at(10, 19))), nowMs = at(23, 0))
        val header = rows.filterIsInstance<TimelineRow.PlaceHeader>().single()
        assertEquals(at(10, 0), header.startMs)
        assertEquals(at(10, 19), header.endMs)
    }

    @Test
    fun groupSpansAllItsEntries() {
        val rows = buildTimelineRows(
            listOf(item(1, at(10, 0), 40_000, at(10, 19)), item(2, at(11, 0), 5_000, at(11, 30))),
            nowMs = at(23, 0),
        )
        val header = rows.filterIsInstance<TimelineRow.PlaceHeader>().single()
        assertEquals(at(11, 30), header.endMs)
    }

    private fun headers(rows: List<TimelineRow>) = rows.filterIsInstance<TimelineRow.PlaceHeader>()

    @Test
    fun unnamedPlacesSplitWhenThePhoneMovesMoreThan100m() {
        val hall = Place(null, 37.58860, 127.02630)
        val nearHall = Place(null, 37.58900, 127.02630) // ~45 m north
        val cafe = Place(null, 37.58290, 127.03580) // ~1 km away
        val rows = buildTimelineRows(
            listOf(
                item(1, at(9, 0), 10_000, at(9, 10), hall),
                item(2, at(10, 0), 10_000, at(10, 10), nearHall),
                item(3, at(13, 0), 10_000, at(13, 10), cafe),
            ),
            nowMs = at(23, 0),
        )
        assertEquals(listOf(at(9, 0) to at(10, 10), at(13, 0) to at(13, 10)), headers(rows).map { it.startMs to it.endMs })
    }

    @Test
    fun noFixIsItsOwnGroupAndNamedPlacesStillGroupByName() {
        val rows = buildTimelineRows(
            listOf(
                item(1, at(9, 0), 10_000, at(9, 10), home),
                item(2, at(10, 0), 10_000, at(10, 10), null),
                item(3, at(11, 0), 10_000, at(11, 10), null),
                item(4, at(12, 0), 10_000, at(12, 10), home.copy(latitude = 37.6)), // same name, far away
            ),
            nowMs = at(23, 0),
        )
        assertEquals(listOf("집", null, "집"), headers(rows).map { it.place?.name })
        assertEquals(null, headers(rows)[1].place)
    }

    @Test
    fun noticesSortInByTimeWithoutOpeningAPlaceGroup() {
        val off = DeviceNotice(7, event = 2, arg = 0, timeMs = at(10, 30), sortTimeMs = at(10, 30))
        val full = DeviceNotice(8, event = 4, arg = 0, timeMs = at(8, 0), sortTimeMs = at(8, 0))
        val entries = mergeTimeline(
            listOf(item(1, at(9, 0), 10_000, at(9, 10)), item(2, at(11, 0), 10_000, at(11, 10))),
            emptyList(),
            listOf(off, full),
        )
        val rows = buildTimelineRows(entries, nowMs = at(23, 0))
        assertEquals(
            listOf("day", "ev-8", "place-1", "item-1", "ev-7", "item-2"),
            rows.map { if (it is TimelineRow.Day) "day" else it.key },
        )
    }

    @Test
    fun aDateSplitByTheUnknownTimeGroupKeepsUniqueKeys() {
        val unknown = item(2, at(10, 0), 10_000, null).copy(startUnixMs = null, endUnixMs = null)
        val rows = buildTimelineRows(
            listOf(item(1, at(9, 0), 10_000, at(9, 10)), unknown, item(3, at(22, 0), 10_000, at(22, 10))),
            nowMs = at(23, 0),
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(3, rows.count { it is TimelineRow.Day })
    }

    @Test
    fun aKnownNoticeStaysBeforeTheUnknownTimeRecordingsThatFollowedIt() {
        val off = DeviceNotice(9, event = 6, arg = 0, timeMs = at(18, 0), sortTimeMs = at(18, 0))
        // Unknown-time entry sorted right after the record before it (here the power-off log).
        val unknown = item(2, at(18, 0), 10_000, null).copy(startUnixMs = null, endUnixMs = null, sortTimeMs = at(18, 0) + 1)
        val entries = mergeTimeline(listOf(item(1, at(13, 0), 10_000, at(13, 10)), unknown), emptyList(), listOf(off))
        assertEquals(listOf("item-1", "ev-9", "item-2"), entries.map {
            when (it) {
                is TimelineEntry.Entry -> "item-${it.item.id}"
                is TimelineEntry.Mark -> "bm-${it.bookmark.id}"
                is TimelineEntry.Notice -> "ev-${it.notice.id}"
            }
        })
    }
}
