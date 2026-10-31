package dev.owl.app.data

import androidx.room.withTransaction
import dev.owl.app.data.db.EventEntity
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.data.db.SegmentEntity
import dev.owl.app.data.db.StatusSampleEntity
import dev.owl.app.data.db.TimeSyncEntity
import dev.owl.app.device.TimeSyncResult
import dev.owl.app.model.SegmentState
import dev.owl.app.protocol.DeviceEvent
import dev.owl.app.protocol.ListEntry
import dev.owl.app.protocol.SegmentHeader
import dev.owl.app.protocol.Status
import dev.owl.app.sync.StoredSegment
import dev.owl.app.sync.SyncDb
import java.io.File

/** [SyncDb] on Room. Time restoration (protocol §4) happens here whenever a boot gets a sync. */
class RoomSyncDb(private val db: OwlDatabase) : SyncDb {
    private val segments = db.segments()

    override suspend fun recordStatus(address: String, status: Status, timeMs: Long) {
        db.status().insert(
            StatusSampleEntity(
                deviceAddress = address, timeMs = timeMs, batteryMv = status.batteryMv, flags = status.flags,
                storageUsedKb = status.storageUsedKb, storageTotalKb = status.storageTotalKb,
                pendingSegments = status.pendingSegments, firmwareVersion = status.firmwareVersion,
                bootCount = status.bootCount,
            ),
        )
    }

    override suspend fun recordTimeSync(address: String, sync: TimeSyncResult) {
        db.withTransaction {
            db.timeSyncs().insert(TimeSyncEntity(0, address, sync.bootCount, sync.deviceUptimeMs, sync.phoneUnixMs))
            restore(address, sync.bootCount)
        }
    }

    private suspend fun restore(address: String, bootCount: Long) {
        val s = db.timeSyncs().latest(address, bootCount) ?: return
        segments.restoreTimes(address, bootCount, s.deviceUptimeMs, s.phoneUnixMs)
        db.events().restoreTimes(address, bootCount, s.deviceUptimeMs, s.phoneUnixMs)
    }

    override suspend fun storedSegment(address: String, id: Long): StoredSegment? =
        segments.find(address, id)?.let { StoredSegment(it.filePath != null && it.state != SegmentState.RECEIVING, it.crc32, it.acked) }

    override suspend fun beginReceiving(address: String, entry: ListEntry, nowMs: Long) {
        if (segments.find(address, entry.id) != null) return
        segments.insert(
            SegmentEntity(
                deviceAddress = address, segmentId = entry.id, type = entry.type, sizeBytes = entry.size,
                sortTimeMs = nowMs, receivedAtMs = nowMs,
            ),
        )
    }

    override suspend fun headerReceived(address: String, id: Long, header: SegmentHeader) {
        db.withTransaction {
            val row = segments.find(address, id) ?: return@withTransaction
            segments.update(row.copy(bootCount = header.bootCount, startUptimeMs = header.startUptimeMs, frameMs = header.frameMs))
            restore(address, header.bootCount)
        }
    }

    override suspend fun progress(address: String, id: Long, receivedBytes: Long) {
        val row = segments.find(address, id) ?: return
        segments.update(row.copy(receivedBytes = receivedBytes))
    }

    override suspend fun saved(
        address: String, id: Long, file: File, crc32: Long, header: SegmentHeader, durationMs: Long?, events: List<DeviceEvent>,
    ) {
        db.withTransaction {
            val row = segments.find(address, id) ?: return@withTransaction
            segments.update(
                row.copy(
                    receivedBytes = row.sizeBytes, bootCount = header.bootCount, startUptimeMs = header.startUptimeMs,
                    frameMs = header.frameMs, durationMs = durationMs, crc32 = crc32, filePath = file.absolutePath,
                    state = if (header.type == dev.owl.app.protocol.SegmentType.AUDIO) SegmentState.RECEIVED else SegmentState.DONE,
                ),
            )
            if (events.isNotEmpty()) {
                db.events().insertAll(
                    events.mapIndexed { i, e ->
                        EventEntity(
                            deviceAddress = address, segmentId = id, idx = i, bootCount = header.bootCount,
                            uptimeMs = e.uptimeMs, unixMs = null, event = e.event, arg = e.arg,
                        )
                    },
                )
            }
            restore(address, header.bootCount)
        }
    }

    /**
     * Recordings from a boot that never got a TIME_SYNC have no real time ("알 수 없음"). Segment
     * ids only grow, so place them on the timeline right after the closest earlier recording
     * with a known time (or just before the next one) instead of at the time we received them.
     */
    suspend fun placeUnknownTimes(address: String) {
        for (s in segments.withUnknownTime(address)) {
            val before = segments.knownTimeBefore(address, s.segmentId)
            val sortTime = when {
                before != null -> before + 1
                else -> segments.knownTimeAfter(address, s.segmentId)?.minus(1) ?: s.receivedAtMs
            }
            if (sortTime != s.sortTimeMs) segments.setSortTime(s.rowId, sortTime)
        }
    }

    override suspend fun acked(address: String, id: Long) {
        val row = segments.find(address, id) ?: return
        segments.update(row.copy(acked = true))
    }

    override suspend fun reset(address: String, id: Long) {
        val row = segments.find(address, id) ?: return
        segments.update(row.copy(receivedBytes = 0, crc32 = null, filePath = null, state = SegmentState.RECEIVING, acked = false))
    }
}
