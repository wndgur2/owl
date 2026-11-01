package dev.owl.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.owl.app.model.SegmentState
import kotlinx.coroutines.flow.Flow

/** A segment row plus its first sentences (bookmarks are counted per entry, see EventDao). */
data class SegmentRow(
    @Embedded val segment: SegmentEntity,
    val preview: String?,
)

private const val PREVIEW = """
    (SELECT group_concat(text, ' ') FROM
        (SELECT text FROM sentences WHERE segmentRowId = s.rowId ORDER BY idx LIMIT 2))"""

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(segment: SegmentEntity): Long

    @Update
    suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segments WHERE deviceAddress = :address AND segmentId = :segmentId")
    suspend fun find(address: String, segmentId: Long): SegmentEntity?

    @Query("SELECT * FROM segments WHERE rowId = :rowId")
    suspend fun get(rowId: Long): SegmentEntity?

    @Query("SELECT * FROM segments WHERE rowId = :rowId")
    fun observe(rowId: Long): Flow<SegmentEntity?>

    // --- timeline entries (EntryIndexer groups segments; entryKey null = not grouped yet) ---

    /** All segments of the newest [limit] entries, oldest first. */
    @Query(
        """SELECT s.*, $PREVIEW AS preview FROM segments s
           WHERE s.type = 1 AND COALESCE(s.entryKey, s.rowId) IN (
               SELECT COALESCE(g.entryKey, g.rowId) AS k FROM segments g
               WHERE g.type = 1 AND (:bookmarkedOnly = 0 OR EXISTS (
                   SELECT 1 FROM events e WHERE e.event = 1 AND e.entryKey = COALESCE(g.entryKey, g.rowId)))
               GROUP BY k ORDER BY MAX(g.sortTimeMs) DESC LIMIT :limit)
           ORDER BY s.sortTimeMs, s.segmentId""",
    )
    fun timelineSegments(limit: Int, bookmarkedOnly: Boolean): Flow<List<SegmentRow>>

    /** Every audio segment with its preview; search filters whole entries in Kotlin. */
    @Query("SELECT s.*, $PREVIEW AS preview FROM segments s WHERE s.type = 1 ORDER BY s.sortTimeMs, s.segmentId")
    fun allAudioRows(): Flow<List<SegmentRow>>

    @Query("SELECT s.*, $PREVIEW AS preview FROM segments s WHERE COALESCE(s.entryKey, s.rowId) = :key ORDER BY s.segmentId")
    fun observeEntry(key: Long): Flow<List<SegmentRow>>

    @Query("SELECT * FROM segments WHERE COALESCE(entryKey, rowId) = :key ORDER BY segmentId")
    suspend fun entrySegments(key: Long): List<SegmentEntity>

    @Query("UPDATE segments SET preserveAudio = :preserve WHERE COALESCE(entryKey, rowId) = :key")
    suspend fun setPreserveEntry(key: Long, preserve: Boolean)

    @Query("DELETE FROM segments WHERE COALESCE(entryKey, rowId) = :key")
    suspend fun deleteEntry(key: Long)

    @Query("UPDATE segments SET entryKey = :key WHERE rowId = :rowId")
    suspend fun setEntryKey(rowId: Long, key: Long)

    // --- places ---

    @Query("SELECT * FROM segments WHERE type = 1 AND latitude IS NOT NULL")
    suspend fun located(): List<SegmentEntity>

    @Query("UPDATE segments SET placeId = :placeId, placeName = :name WHERE rowId = :rowId")
    suspend fun setPlace(rowId: Long, placeId: Long?, name: String?)

    @Query("SELECT * FROM segments WHERE type = 1 AND state = :state ORDER BY sortTimeMs")
    suspend fun withState(state: SegmentState): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE type = 1 AND filePath IS NOT NULL AND audioDeleted = 0")
    suspend fun withAudio(): List<SegmentEntity>

    /** Entries (merged segments, decisions.md #30) that still have audio, and the bytes it takes. */
    @Query("SELECT COUNT(DISTINCT COALESCE(entryKey, rowId)) AS entryCount, COALESCE(SUM(sizeBytes), 0) AS bytes FROM segments WHERE type = 1 AND filePath IS NOT NULL AND audioDeleted = 0")
    fun audioUsage(): Flow<AudioUsageRow>

    @Query("SELECT COUNT(*) FROM segments WHERE deviceAddress = :address AND acked = 0")
    suspend fun unackedCount(address: String): Int

    /** Protocol §4: once a boot has a TIME_SYNC, every record of that boot gets its real time. */
    @Query(
        """UPDATE segments SET startUnixMs = :phoneUnixMs + (startUptimeMs - :deviceUptimeMs),
                               sortTimeMs = :phoneUnixMs + (startUptimeMs - :deviceUptimeMs)
           WHERE deviceAddress = :address AND bootCount = :bootCount AND startUptimeMs IS NOT NULL AND startUnixMs IS NULL""",
    )
    suspend fun restoreTimes(address: String, bootCount: Long, deviceUptimeMs: Long, phoneUnixMs: Long)

    @Query("SELECT * FROM segments WHERE type = 1 AND startUnixMs IS NOT NULL AND latitude IS NULL")
    suspend fun withoutPlace(): List<SegmentEntity>

    @Query("UPDATE segments SET preserveAudio = :preserve WHERE rowId = :rowId")
    suspend fun setPreserve(rowId: Long, preserve: Boolean)

    @Query("UPDATE segments SET audioDeleted = 1 WHERE rowId = :rowId")
    suspend fun markAudioDeleted(rowId: Long)

    @Query("UPDATE segments SET state = :state WHERE rowId = :rowId")
    suspend fun setState(rowId: Long, state: SegmentState)

    @Query("DELETE FROM segments WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("SELECT * FROM segments WHERE type = 1 ORDER BY sortTimeMs")
    suspend fun allAudio(): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE deviceAddress = :address AND startUnixMs IS NULL")
    suspend fun withUnknownTime(address: String): List<SegmentEntity>

    /** Real time of the closest earlier segment (ids are monotonic, so id order = time order). */
    @Query("SELECT startUnixMs FROM segments WHERE deviceAddress = :address AND segmentId < :id AND startUnixMs IS NOT NULL ORDER BY segmentId DESC LIMIT 1")
    suspend fun knownTimeBefore(address: String, id: Long): Long?

    @Query("SELECT startUnixMs FROM segments WHERE deviceAddress = :address AND segmentId > :id AND startUnixMs IS NOT NULL ORDER BY segmentId ASC LIMIT 1")
    suspend fun knownTimeAfter(address: String, id: Long): Long?

    @Query("UPDATE segments SET sortTimeMs = :sortTimeMs WHERE rowId = :rowId")
    suspend fun setSortTime(rowId: Long, sortTimeMs: Long)

    @Query("SELECT MAX(bootCount) FROM segments WHERE deviceAddress = :address")
    suspend fun maxBootCount(address: String): Long?

    @Query("SELECT MAX(segmentId) FROM segments WHERE deviceAddress = :address")
    suspend fun maxSegmentId(address: String): Long?
}

data class AudioUsageRow(val entryCount: Int, val bytes: Long)

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query(
        """UPDATE events SET unixMs = :phoneUnixMs + (uptimeMs - :deviceUptimeMs)
           WHERE deviceAddress = :address AND bootCount = :bootCount AND unixMs IS NULL""",
    )
    suspend fun restoreTimes(address: String, bootCount: Long, deviceUptimeMs: Long, phoneUnixMs: Long)

    @Query(
        """SELECT * FROM events WHERE deviceAddress = :address AND bootCount = :bootCount AND event = 1
           AND uptimeMs >= :fromUptime AND uptimeMs <= :toUptime ORDER BY uptimeMs""",
    )
    fun bookmarks(address: String, bootCount: Long, fromUptime: Long, toUptime: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE event = 1 ORDER BY bootCount, uptimeMs")
    suspend fun allBookmarks(): List<EventEntity>

    /** Bookmarks and "recording off" events: what EntryIndexer needs. */
    @Query("SELECT * FROM events WHERE event IN (1, 2)")
    suspend fun forGrouping(): List<EventEntity>

    @Query("UPDATE events SET entryKey = :key WHERE rowId = :rowId")
    suspend fun setEntryKey(rowId: Long, key: Long)

    @Query("SELECT entryKey AS k, COUNT(*) AS n FROM events WHERE event = 1 AND entryKey IS NOT NULL AND entryKey != -1 GROUP BY entryKey")
    fun bookmarkCounts(): Flow<List<KeyCount>>

    @Query("SELECT * FROM events WHERE event = 1 AND entryKey = :key ORDER BY uptimeMs")
    fun entryBookmarks(key: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE event = 1 AND entryKey = :key ORDER BY uptimeMs")
    suspend fun entryBookmarksOnce(key: Long): List<EventEntity>

    @Query("DELETE FROM events WHERE event = 1 AND entryKey = :key")
    suspend fun deleteEntryBookmarks(key: Long)

    /**
     * Bookmarks outside any recording (A9). Unknown-time ones sort with the event log they came
     * in (its sortTimeMs follows segment id order).
     */
    @Query(
        """SELECT e.rowId AS id, e.unixMs AS timeMs, COALESCE(e.unixMs, s.sortTimeMs, 0) AS sortTimeMs FROM events e
           LEFT JOIN segments s ON s.deviceAddress = e.deviceAddress AND s.segmentId = e.segmentId
           WHERE e.event = 1 AND e.entryKey = -1 AND COALESCE(e.unixMs, s.sortTimeMs, 0) >= :sinceMs
           ORDER BY sortTimeMs""",
    )
    fun standaloneBookmarks(sinceMs: Long): Flow<List<StandaloneRow>>

    /** Device events other than bookmarks (recording off/on, storage full, battery low, power off/on), same sorting. */
    @Query(
        """SELECT e.rowId AS id, e.event AS event, e.arg AS arg, e.unixMs AS timeMs,
           COALESCE(e.unixMs, s.sortTimeMs, 0) AS sortTimeMs FROM events e
           LEFT JOIN segments s ON s.deviceAddress = e.deviceAddress AND s.segmentId = e.segmentId
           WHERE e.event BETWEEN 2 AND 7 AND COALESCE(e.unixMs, s.sortTimeMs, 0) >= :sinceMs
           ORDER BY sortTimeMs, e.bootCount, e.uptimeMs""",
    )
    fun deviceEvents(sinceMs: Long): Flow<List<DeviceEventRow>>
}

data class DeviceEventRow(val id: Long, val event: Int, val arg: Int, val timeMs: Long?, val sortTimeMs: Long)

data class KeyCount(val k: Long, val n: Int)

data class StandaloneRow(val id: Long, val timeMs: Long?, val sortTimeMs: Long)

@Dao
interface TimeSyncDao {
    @Insert
    suspend fun insert(sync: TimeSyncEntity)

    @Query("SELECT * FROM time_syncs WHERE deviceAddress = :address AND bootCount = :bootCount ORDER BY phoneUnixMs DESC LIMIT 1")
    suspend fun latest(address: String, bootCount: Long): TimeSyncEntity?
}

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(location: LocationEntity)

    @Query("SELECT * FROM locations WHERE timeMs BETWEEN :from AND :to ORDER BY ABS(timeMs - :around) LIMIT 1")
    suspend fun nearest(around: Long, from: Long, to: Long): LocationEntity?
}

@Dao
interface PlaceDao {
    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Query("SELECT * FROM places")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT name FROM places ORDER BY name")
    fun names(): Flow<List<String>>

    @Query(
        """SELECT p.*, (SELECT COUNT(DISTINCT COALESCE(s.entryKey, s.rowId)) FROM segments s WHERE s.placeId = p.rowId) AS entryCount
           FROM places p ORDER BY p.name""",
    )
    fun withCounts(): Flow<List<PlaceWithCount>>

    @Query("UPDATE places SET name = :name WHERE rowId = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM places WHERE rowId = :id")
    suspend fun delete(id: Long)
}

data class PlaceWithCount(@Embedded val place: PlaceEntity, val entryCount: Int)

@Dao
interface SentenceDao {
    @Insert
    suspend fun insertAll(sentences: List<SentenceEntity>)

    @Query("DELETE FROM sentences WHERE segmentRowId = :segmentRowId")
    suspend fun deleteFor(segmentRowId: Long)

    @Query("SELECT * FROM sentences WHERE segmentRowId = :segmentRowId ORDER BY idx")
    fun observeFor(segmentRowId: Long): Flow<List<SentenceEntity>>

    @Query("SELECT * FROM sentences WHERE segmentRowId = :segmentRowId ORDER BY idx")
    suspend fun forSegment(segmentRowId: Long): List<SentenceEntity>

    @Query("SELECT * FROM sentences WHERE segmentRowId IN (:segmentRowIds) ORDER BY segmentRowId, idx")
    fun observeForSegments(segmentRowIds: List<Long>): Flow<List<SentenceEntity>>

    /** Plain LIKE search; fine for one person's records. Escape `%`/`_` in [pattern] beforehand. */
    @Query("SELECT * FROM sentences WHERE text LIKE :pattern ESCAPE '\\' ORDER BY segmentRowId DESC, idx LIMIT 1000")
    fun search(pattern: String): Flow<List<SentenceEntity>>

    @Transaction
    suspend fun replace(segmentRowId: Long, sentences: List<SentenceEntity>) {
        deleteFor(segmentRowId)
        insertAll(sentences)
    }
}

@Dao
interface StatusDao {
    @Insert
    suspend fun insert(sample: StatusSampleEntity)

    @Query("SELECT * FROM status_samples WHERE deviceAddress = :address ORDER BY timeMs DESC LIMIT 1")
    fun latest(address: String): Flow<StatusSampleEntity?>

    @Query("SELECT * FROM status_samples WHERE deviceAddress = :address AND timeMs >= :sinceMs ORDER BY timeMs")
    suspend fun since(address: String, sinceMs: Long): List<StatusSampleEntity>
}
