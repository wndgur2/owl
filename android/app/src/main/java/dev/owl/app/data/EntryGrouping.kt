package dev.owl.app.data

import dev.owl.app.protocol.EventType

/**
 * Adjacent recordings closer than this become one timeline entry (PRD A7): from the end of one
 * segment to the start of the next, in restored wall-clock time. The pendant starts a new
 * segment after every VAD pause (protocol §3.2) and at most every 300 s, so one conversation is
 * usually several segments.
 */
const val ENTRY_MERGE_GAP_MS: Long = 2 * 60_000L

/** Marks a bookmark that belongs to no entry (shown on its own on the timeline, A9). */
const val STANDALONE_BOOKMARK: Long = -1L

/**
 * Pure rules for building timeline entries out of segments, and for attaching bookmarks to them.
 * No Android or Room types, so it is unit-tested directly (`EntryGroupingTest`).
 */
object EntryGrouping {
    data class Seg(
        val rowId: Long,
        val device: String,
        /** Monotonic across reboots, so id order = recording order (protocol §3.1). */
        val segmentId: Long,
        val bootCount: Long?,
        val startUptimeMs: Long?,
        /** Null = time unknown (no TIME_SYNC for that boot): merged only by uptime, see [group]. */
        val startUnixMs: Long?,
        val durationMs: Long,
    ) {
        val endUptimeMs: Long? get() = startUptimeMs?.plus(durationMs)
    }

    data class Event(val rowId: Long, val device: String, val bootCount: Long, val uptimeMs: Long, val event: Int)

    data class Result(
        /** Segment row id → entry key (row id of the entry's first segment). */
        val entryOf: Map<Long, Long>,
        /** Bookmark event row id → entry key, or [STANDALONE_BOOKMARK]. */
        val bookmarkEntry: Map<Long, Long>,
    )

    /**
     * Two neighbouring segments (in id order, same device) merge when all hold:
     * - same boot, and either both have a known real time or both do not;
     * - the gap from the end of the first to the start of the second is at most [gapMs] — in real
     *   time, or for a boot that never got a TIME_SYNC in uptime, which within one boot is just
     *   as good a clock (decisions #48, amends #30);
     * - no "recording off" event happened between them (the user turned recording off, so what
     *   follows is a different moment even if it came soon after).
     */
    fun group(segs: List<Seg>, events: List<Event>, gapMs: Long = ENTRY_MERGE_GAP_MS): Result {
        val offs = events.filter { it.event == EventType.RECORDING_OFF }
            .groupBy { it.device to it.bootCount }
        val entryOf = HashMap<Long, Long>()
        val ranges = ArrayList<Range>() // one per entry, for bookmark assignment
        var prev: Seg? = null
        var current: Range? = null
        for (s in segs.sortedWith(compareBy({ it.device }, { it.segmentId }))) {
            val p = prev
            val merge = p != null && current != null && canMerge(p, s, gapMs, offs)
            if (merge) {
                current!!.end = s.endUptimeMs
            } else {
                current = Range(s.rowId, s.device, s.bootCount, s.startUptimeMs, s.endUptimeMs).also { ranges += it }
            }
            entryOf[s.rowId] = current!!.key
            prev = s
        }
        val bookmarkEntry = HashMap<Long, Long>()
        val byBoot = ranges.filter { it.boot != null && it.start != null && it.end != null }
            .groupBy { it.device to it.boot!! }
        for (e in events) {
            if (e.event != EventType.BOOKMARK) continue
            val hit = byBoot[e.device to e.bootCount]?.firstOrNull { e.uptimeMs in it.start!!..it.end!! }
            bookmarkEntry[e.rowId] = hit?.key ?: STANDALONE_BOOKMARK
        }
        return Result(entryOf, bookmarkEntry)
    }

    private class Range(val key: Long, val device: String, val boot: Long?, val start: Long?, var end: Long?)

    private fun canMerge(a: Seg, b: Seg, gapMs: Long, offs: Map<Pair<String, Long>, List<Event>>): Boolean {
        if (a.device != b.device) return false
        if (a.bootCount == null || a.bootCount != b.bootCount) return false
        val aEnd = a.endUptimeMs ?: return false
        val bBegin = b.startUptimeMs ?: return false
        val aStart = a.startUnixMs
        val bStart = b.startUnixMs
        val gap = when {
            aStart != null && bStart != null -> bStart - (aStart + a.durationMs)
            aStart == null && bStart == null -> bBegin - aEnd
            else -> return false // can't happen within one boot; never mix known and unknown
        }
        if (gap > gapMs) return false
        val turnedOff = offs[a.device to a.bootCount].orEmpty().any { it.uptimeMs in (a.startUptimeMs ?: aEnd)..bBegin }
        return !turnedOff
    }

    /**
     * Where entry-relative time [uptimeMs] falls when the entry's segments are played back to
     * back: inside a segment → its offset; inside a gap → the end of the segment before it.
     * [parts] are (start uptime, duration) in order.
     */
    fun offsetInEntry(parts: List<Pair<Long, Long>>, uptimeMs: Long): Long {
        var offset = 0L
        for ((start, dur) in parts) {
            if (uptimeMs < start) return offset
            if (uptimeMs <= start + dur) return offset + (uptimeMs - start)
            offset += dur
        }
        return offset
    }
}
