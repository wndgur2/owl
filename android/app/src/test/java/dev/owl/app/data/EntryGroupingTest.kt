package dev.owl.app.data

import dev.owl.app.data.EntryGrouping.Event
import dev.owl.app.data.EntryGrouping.Seg
import dev.owl.app.protocol.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryGroupingTest {
    private val t0 = 1_793_440_000_000L
    private val min = 60_000L

    /** A segment in boot 3 whose uptime equals its offset from t0 (so unix = t0 + uptime). */
    private fun seg(row: Long, startMin: Double, durSec: Long, boot: Long? = 3, known: Boolean = true, device: String = "d") = Seg(
        rowId = row, device = device, segmentId = row, bootCount = boot,
        startUptimeMs = (startMin * min).toLong(), startUnixMs = if (known) t0 + (startMin * min).toLong() else null,
        durationMs = durSec * 1000,
    )

    private fun groupsOf(r: EntryGrouping.Result, vararg rows: Long) = rows.map { r.entryOf.getValue(it) }

    @Test
    fun shortGapsMergeLongGapsSplit() {
        // 0–5 min, 6–8 min (gap 1 min), 10:30–11 (gap 2.5 min)
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 300), seg(2, 6.0, 120), seg(3, 10.5, 30)), emptyList())
        assertEquals(listOf(1L, 1L, 3L), groupsOf(r, 1, 2, 3))
    }

    @Test
    fun exactlyTwoMinutesStillMerges() {
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 60), seg(2, 3.0, 60)), emptyList())
        assertEquals(listOf(1L, 1L), groupsOf(r, 1, 2))
    }

    @Test
    fun unknownTimeMergesByUptimeWithinItsBoot() {
        // decisions #48: a boot without TIME_SYNC still has uptime; 1.5 min gap merges, 2.5 min splits.
        val r = EntryGrouping.group(
            listOf(seg(1, 0.0, 60, known = false), seg(2, 2.5, 60, known = false), seg(3, 6.0, 60, known = false)),
            emptyList(),
        )
        assertEquals(listOf(1L, 1L, 3L), groupsOf(r, 1, 2, 3))
    }

    @Test
    fun unknownAndKnownNeverMerge() {
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 60, known = false), seg(2, 1.5, 60, boot = 3)), emptyList())
        assertEquals(listOf(1L, 2L), groupsOf(r, 1, 2))
    }

    @Test
    fun bookmarkInAnUnknownTimeConversationBelongsToIt() {
        val mark = Event(11, "d", 3, (1.5 * min).toLong(), EventType.BOOKMARK) // in the gap between 1 and 2
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 60, known = false), seg(2, 2.5, 60, known = false)), listOf(mark))
        assertEquals(1L, r.bookmarkEntry.getValue(11))
    }

    @Test
    fun differentBootsNeverMerge() {
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 60, boot = 3), seg(2, 1.5, 60, boot = 4)), emptyList())
        assertEquals(listOf(1L, 2L), groupsOf(r, 1, 2))
    }

    @Test
    fun recordingOffBetweenSplits() {
        val off = Event(10, "d", 3, (1.2 * min).toLong(), EventType.RECORDING_OFF)
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 60), seg(2, 1.5, 60)), listOf(off))
        assertEquals(listOf(1L, 2L), groupsOf(r, 1, 2))
    }

    @Test
    fun devicesAreSeparate() {
        val r = EntryGrouping.group(listOf(seg(1, 0.0, 60, device = "a"), seg(2, 1.5, 60, device = "b")), emptyList())
        assertEquals(listOf(1L, 2L), groupsOf(r, 1, 2))
    }

    @Test
    fun bookmarksInsideOrInGapsBelongToTheEntryOthersStandAlone() {
        val segs = listOf(seg(1, 0.0, 60), seg(2, 2.0, 60), seg(3, 20.0, 60))
        val inside = Event(11, "d", 3, (0.5 * min).toLong(), EventType.BOOKMARK)
        val inGap = Event(12, "d", 3, (1.5 * min).toLong(), EventType.BOOKMARK)
        val silence = Event(13, "d", 3, 10 * min, EventType.BOOKMARK)
        val otherBoot = Event(14, "d", 4, (0.5 * min).toLong(), EventType.BOOKMARK)
        val r = EntryGrouping.group(segs, listOf(inside, inGap, silence, otherBoot))
        assertEquals(1L, r.bookmarkEntry[11])
        assertEquals(1L, r.bookmarkEntry[12])
        assertEquals(STANDALONE_BOOKMARK, r.bookmarkEntry[13])
        assertEquals(STANDALONE_BOOKMARK, r.bookmarkEntry[14])
    }

    @Test
    fun offsetsAreContinuousAcrossSegments() {
        val parts = listOf(0L to 60_000L, 90_000L to 30_000L) // second starts after a 30 s gap
        assertEquals(10_000L, EntryGrouping.offsetInEntry(parts, 10_000))
        assertEquals(60_000L, EntryGrouping.offsetInEntry(parts, 75_000)) // in the gap → end of first
        assertEquals(65_000L, EntryGrouping.offsetInEntry(parts, 95_000))
        assertEquals(90_000L, EntryGrouping.offsetInEntry(parts, 200_000))
    }
}
