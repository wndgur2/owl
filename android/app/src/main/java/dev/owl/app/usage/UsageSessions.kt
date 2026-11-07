package dev.owl.app.usage

import dev.owl.app.data.ENTRY_MERGE_GAP_MS
import dev.owl.app.data.db.AppUsageEntity
import dev.owl.app.model.AppUsageCard
import dev.owl.app.model.AppUsageSummary

/** What we need from one system usage event (A18). */
data class UsageEvent(val packageName: String, val type: Type, val timeMs: Long) {
    enum class Type {
        /** The app came to the foreground. */
        RESUMED,

        /** The app left the foreground. */
        PAUSED,

        /** Screen off or shutdown: every app stops being used. [packageName] is ignored. */
        ALL_STOPPED,
    }
}

data class UsageSession(val packageName: String, val startMs: Long, val endMs: Long)

/** Wall clock against the time since boot; it changes only when someone sets the clock. */
data class ClockReading(val bootCount: Int, val wallMinusElapsedMs: Long)

/**
 * [finished] sessions can be stored. Everything from [nextCursorMs] on must be read again, because
 * a session starting there may still be running or may still be joined by the same app's next one.
 */
data class UsageWindow(val finished: List<UsageSession>, val nextCursorMs: Long)

/** Pure rules for turning system usage events into sessions and day totals (decisions #51). */
object UsageSessions {
    /**
     * The same app back within this, with no other app in between, is one session: moving between
     * its screens. With another app in between, joining would count that app's time twice.
     */
    const val MERGE_GAP_MS = 5_000L

    /** Shorter sessions are launch trampolines, not use. */
    const val MIN_SESSION_MS = 1_000L

    /** Smaller clock changes are ordinary drift; the system leaves its events alone for those too. */
    private const val CLOCK_CHANGE_MS = 2_000L

    /**
     * When the clock is set, the system moves every stored event by the same amount. A cursor must
     * move with them, or events already read land after it and get stored twice (seen on API 37).
     * Across a reboot the two readings cannot be compared, so nothing moves.
     */
    fun clockShiftMs(before: ClockReading?, now: ClockReading): Long {
        if (before == null || before.bootCount != now.bootCount) return 0
        val shift = now.wallMinusElapsedMs - before.wallMinusElapsedMs
        return if (kotlin.math.abs(shift) > CLOCK_CHANGE_MS) shift else 0
    }

    /** [events] are everything known up to [toMs]. */
    fun build(events: List<UsageEvent>, toMs: Long): UsageWindow {
        val open = LinkedHashMap<String, Long>()
        val closed = HashMap<String, MutableList<UsageSession>>()
        var lastResumed: String? = null

        fun close(packageName: String, atMs: Long) {
            val start = open.remove(packageName) ?: return
            closed.getOrPut(packageName) { ArrayList() } += UsageSession(packageName, start, atMs)
        }

        for (e in events.sortedBy { it.timeMs }) {
            when (e.type) {
                UsageEvent.Type.RESUMED -> {
                    if (e.packageName !in open) {
                        val sessions = closed[e.packageName]
                        val last = sessions?.lastOrNull()
                        if (last != null && lastResumed == e.packageName && e.timeMs - last.endMs <= MERGE_GAP_MS) {
                            sessions.removeAt(sessions.lastIndex)
                            open[e.packageName] = last.startMs
                        } else {
                            open[e.packageName] = e.timeMs
                        }
                    }
                    lastResumed = e.packageName
                }
                UsageEvent.Type.PAUSED -> close(e.packageName, e.timeMs)
                UsageEvent.Type.ALL_STOPPED -> open.keys.toList().forEach { close(it, e.timeMs) }
            }
        }

        val (done, pending) = closed.values.flatten().partition { it.endMs + MERGE_GAP_MS <= toMs }
        val nextCursor = (open.values + pending.map { it.startMs }).minOrNull()?.coerceAtMost(toMs) ?: toMs
        return UsageWindow(done.filter { it.endMs - it.startMs >= MIN_SESSION_MS }.sortedBy { it.startMs }, nextCursor)
    }

    /**
     * Timeline cards: sessions at most [ENTRY_MERGE_GAP_MS] apart become one, the same rule as
     * recordings (A7), so a day of phone use is a few cards rather than hundreds. [rows] oldest first.
     */
    fun cards(rows: List<AppUsageEntity>): List<AppUsageCard> {
        val groups = ArrayList<MutableList<AppUsageEntity>>()
        var groupEnd = Long.MIN_VALUE
        for (row in rows) {
            if (groups.isEmpty() || row.startMs - groupEnd > ENTRY_MERGE_GAP_MS) {
                groups += mutableListOf(row)
                groupEnd = row.endMs
            } else {
                groups.last() += row
                groupEnd = maxOf(groupEnd, row.endMs)
            }
        }
        return groups.map { group ->
            val start = group.first().startMs
            val end = group.maxOf { it.endMs }
            AppUsageCard(id = group.first().rowId, startMs = start, endMs = end, apps = summarize(group, start, end))
        }
    }

    /** Each app's use within [fromMs, toMs), most used first. A session crossing midnight counts in both days. */
    fun summarize(rows: List<AppUsageEntity>, fromMs: Long, toMs: Long): List<AppUsageSummary> =
        rows.groupBy { it.packageName }.map { (packageName, sessions) ->
            AppUsageSummary(
                packageName = packageName,
                label = sessions.maxBy { it.startMs }.label,
                totalMs = sessions.sumOf { it.endMs.coerceAtMost(toMs) - it.startMs.coerceAtLeast(fromMs) },
                sessions = sessions.size,
            )
        }.sortedWith(compareByDescending<AppUsageSummary> { it.totalMs }.thenBy { it.label })
}
