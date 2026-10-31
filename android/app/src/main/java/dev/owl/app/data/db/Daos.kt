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

/** A segment row plus what the timeline needs from other tables. */
data class SegmentRow(
    @Embedded val segment: SegmentEntity,
    val preview: String?,
    val bookmarkCount: Int,
)

/** A bookmark falls inside a recording if it is in the same boot and within its time span. */
private const val BOOKMARK_COUNT = """
    (SELECT COUNT(*) FROM events e
      WHERE e.deviceAddress = s.deviceAddress AND e.event = 1 AND e.bootCount = s.bootCount
        AND e.uptimeMs >= s.startUptimeMs
        AND e.uptimeMs <= s.startUptimeMs + COALESCE(s.durationMs, 0))"""

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

    @Query(
        """SELECT s.*, $PREVIEW AS preview, $BOOKMARK_COUNT AS bookmarkCount FROM segments s
           WHERE s.type = 1 AND (:bookmarkedOnly = 0 OR $BOOKMARK_COUNT > 0)
           ORDER BY s.sortTimeMs DESC, s.segmentId DESC LIMIT :limit""",
    )
    fun timeline(limit: Int, bookmarkedOnly: Boolean): Flow<List<SegmentRow>>

    @Query("SELECT s.*, $PREVIEW AS preview, $BOOKMARK_COUNT AS bookmarkCount FROM segments s WHERE s.rowId = :rowId")
    fun observeRow(rowId: Long): Flow<SegmentRow?>

    @Query(
        """SELECT s.*, $PREVIEW AS preview, $BOOKMARK_COUNT AS bookmarkCount FROM segments s
           WHERE s.type = 1 AND s.sortTimeMs >= :sinceMs
             AND (:place IS NULL OR s.placeName = :place)
             AND (:preservedOnly = 0 OR s.preserveAudio = 1)
             AND (:bookmarkedOnly = 0 OR $BOOKMARK_COUNT > 0)
           ORDER BY s.sortTimeMs DESC LIMIT 500""",
    )
    fun filtered(sinceMs: Long, place: String?, bookmarkedOnly: Boolean, preservedOnly: Boolean): Flow<List<SegmentRow>>

    @Query("SELECT DISTINCT placeName FROM segments WHERE placeName IS NOT NULL ORDER BY placeName")
    fun placeNames(): Flow<List<String>>

    @Query("SELECT * FROM segments WHERE type = 1 AND state = :state ORDER BY sortTimeMs")
    suspend fun withState(state: SegmentState): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE type = 1 AND filePath IS NOT NULL AND audioDeleted = 0")
    suspend fun withAudio(): List<SegmentEntity>

    @Query("SELECT COUNT(*) AS entryCount, COALESCE(SUM(sizeBytes), 0) AS bytes FROM segments WHERE type = 1 AND filePath IS NOT NULL AND audioDeleted = 0")
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

    @Query("UPDATE segments SET placeName = :name WHERE latitude IS NOT NULL AND placeName IS NULL AND ABS(latitude - :lat) < :dLat AND ABS(longitude - :lon) < :dLon")
    suspend fun nameNearby(name: String, lat: Double, lon: Double, dLat: Double, dLon: Double)

    @Query("DELETE FROM segments WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("SELECT * FROM segments WHERE type = 1 ORDER BY sortTimeMs")
    suspend fun allAudio(): List<SegmentEntity>
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
}

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
}

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
