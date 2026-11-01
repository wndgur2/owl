package dev.owl.app.ui.timeline

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

    private fun item(id: Long, start: Long, durationMs: Long, end: Long?) = TimelineItem(
        id = id, startUnixMs = start, durationMs = durationMs, durationIsEstimate = false, place = home, preview = null,
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
}
