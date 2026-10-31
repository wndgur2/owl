package dev.owl.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.owl.app.model.SegmentState

/**
 * One segment pulled from the pendant (audio or event log). The raw bytes live in [filePath]
 * exactly as they came over BLE (protocol §3: same bytes on flash, on air and on the phone).
 */
@Entity(
    tableName = "segments",
    indices = [
        Index(value = ["deviceAddress", "segmentId"], unique = true),
        Index(value = ["sortTimeMs"]),
        Index(value = ["deviceAddress", "bootCount"]),
    ],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deviceAddress: String,
    /** Device segment id (u32, monotonic across reboots). */
    val segmentId: Long,
    /** protocol SegmentType: 1 audio, 2 events. */
    val type: Int,
    /** Whole segment size from LIST (header + payload). */
    val sizeBytes: Long,
    /** Contiguous bytes stored so far (the `.part` file length). FETCH resumes here. */
    val receivedBytes: Long = 0,
    /** From the segment header, known once the first 32 bytes arrived. */
    val bootCount: Long? = null,
    val startUptimeMs: Long? = null,
    /** Restored real time (protocol §4). Null = "알 수 없음" until a TIME_SYNC for that boot exists. */
    val startUnixMs: Long? = null,
    /** Timeline order: [startUnixMs] when known, otherwise the time we received it. */
    val sortTimeMs: Long,
    val durationMs: Long? = null,
    val frameMs: Int? = null,
    val crc32: Long? = null,
    /** Final file once complete; null while receiving. */
    val filePath: String? = null,
    val state: SegmentState = SegmentState.RECEIVING,
    /** ACK_SEGMENT accepted by the pendant (it may delete its copy). */
    val acked: Boolean = false,
    /** A14: "음성 보존" — retention never deletes this audio. */
    val preserveAudio: Boolean = false,
    /** A14: audio file removed by retention; text stays. */
    val audioDeleted: Boolean = false,
    val receivedAtMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeName: String? = null,
)

/** One entry of an event-log segment (protocol §3.3). Bookmarks are event 1. */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["deviceAddress", "segmentId", "idx"], unique = true),
        Index(value = ["deviceAddress", "bootCount", "uptimeMs"]),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deviceAddress: String,
    val segmentId: Long,
    val idx: Int,
    val bootCount: Long,
    val uptimeMs: Long,
    val unixMs: Long?,
    val event: Int,
    val arg: Int,
)

/** A TIME_SYNC pair: at phone time [phoneUnixMs] the device was at ([bootCount], [deviceUptimeMs]). */
@Entity(tableName = "time_syncs", indices = [Index(value = ["deviceAddress", "bootCount"])])
data class TimeSyncEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deviceAddress: String,
    val bootCount: Long,
    val deviceUptimeMs: Long,
    val phoneUnixMs: Long,
)

/** Phone GPS fix (A5). */
@Entity(tableName = "locations", indices = [Index(value = ["timeMs"])])
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
)

/** A named place. Names are never looked up online (PRD N8: no reverse geocoding). */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Double = 150.0,
)

/** One transcript sentence with its timing inside the recording (A6, A8). */
@Entity(
    tableName = "sentences",
    foreignKeys = [
        ForeignKey(
            entity = SegmentEntity::class,
            parentColumns = ["rowId"],
            childColumns = ["segmentRowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["segmentRowId", "idx"], unique = true)],
)
data class SentenceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val segmentRowId: Long,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** STATUS snapshots; the history gives the battery time-left estimate. */
@Entity(tableName = "status_samples", indices = [Index(value = ["deviceAddress", "timeMs"])])
data class StatusSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deviceAddress: String,
    val timeMs: Long,
    val batteryMv: Int,
    val flags: Int,
    val storageUsedKb: Long,
    val storageTotalKb: Long,
    val pendingSegments: Int,
    val firmwareVersion: String,
    val bootCount: Long,
)
