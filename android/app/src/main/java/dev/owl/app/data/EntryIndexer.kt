package dev.owl.app.data

import androidx.room.withTransaction
import dev.owl.app.data.db.OwlDatabase

/**
 * Stores the result of [EntryGrouping] in the DB (segments.entryKey, events.entryKey), so the
 * timeline can page by entry in SQL. Rebuilt from scratch after every sync pass, on app start
 * and after deletions — grouping depends only on segment times and events, so a full rebuild is
 * simple and cheap at one person's scale (thousands of rows).
 */
class EntryIndexer(private val db: OwlDatabase) {
    suspend fun rebuild() {
        db.withTransaction {
            val segs = db.segments().allAudio()
            val events = db.events().forGrouping()
            val result = EntryGrouping.group(
                segs.map {
                    EntryGrouping.Seg(
                        it.rowId, it.deviceAddress, it.segmentId, it.bootCount, it.startUptimeMs, it.startUnixMs,
                        it.durationMs ?: estimateDurationMs(it),
                    )
                },
                events.map { EntryGrouping.Event(it.rowId, it.deviceAddress, it.bootCount, it.uptimeMs, it.event) },
            )
            for (s in segs) {
                val key = result.entryOf.getValue(s.rowId)
                if (s.entryKey != key) db.segments().setEntryKey(s.rowId, key)
            }
            for (e in events) {
                val key = result.bookmarkEntry[e.rowId] ?: continue
                if (e.entryKey != key) db.events().setEntryKey(e.rowId, key)
            }
        }
    }
}
