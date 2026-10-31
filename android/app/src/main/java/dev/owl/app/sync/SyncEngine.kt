package dev.owl.app.sync

import dev.owl.app.device.OwlClient
import dev.owl.app.device.TimeSyncResult
import dev.owl.app.device.TransportException
import dev.owl.app.protocol.DeviceEvent
import dev.owl.app.protocol.ListEntry
import dev.owl.app.protocol.ProtocolException
import dev.owl.app.protocol.ResultCode
import dev.owl.app.protocol.SegmentHeader
import dev.owl.app.protocol.SegmentType
import dev.owl.app.protocol.Segments
import dev.owl.app.protocol.Status
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What the sync engine needs to persist. Implemented with Room in `data/`, in memory in tests. */
interface SyncDb {
    suspend fun recordStatus(address: String, status: Status, timeMs: Long)

    /** Stores the pair and restores real time for every record of that boot (protocol §4). */
    suspend fun recordTimeSync(address: String, sync: TimeSyncResult)

    suspend fun storedSegment(address: String, id: Long): StoredSegment?

    /** Creates the row (state RECEIVING) if it does not exist yet. */
    suspend fun beginReceiving(address: String, entry: ListEntry, nowMs: Long)

    suspend fun headerReceived(address: String, id: Long, header: SegmentHeader)

    suspend fun progress(address: String, id: Long, receivedBytes: Long)

    /** File is durable: mark RECEIVED and, for event logs, insert the events — in one transaction. */
    suspend fun saved(address: String, id: Long, file: File, crc32: Long, header: SegmentHeader, durationMs: Long?, events: List<DeviceEvent>)

    suspend fun acked(address: String, id: Long)

    /** The pendant rejected our CRC: forget what we have and fetch again from 0. */
    suspend fun reset(address: String, id: Long)
}

data class StoredSegment(val saved: Boolean, val crc32: Long?, val acked: Boolean)

data class SyncReport(
    val listed: Int,
    val fetched: Int,
    val acked: Int,
    val skipped: Int,
    val status: Status?,
)

/** Thrown when one pass cannot continue (link lost, stalled transfer). The coordinator retries. */
class SyncFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One sync pass over an already connected [OwlClient] — protocol §5:
 * STATUS → TIME_SYNC → LIST (paged) → for each segment FETCH (resuming) → CRC32 → save + fsync → ACK.
 *
 * The ordering rule that makes N4 (no data loss) work: a segment is ACKed only after
 * [SegmentFiles.commit] returned and the DB row says RECEIVED. If the app dies in between, the
 * next LIST still contains the segment and we just ACK it again without refetching.
 */
class SyncEngine(
    private val files: SegmentFiles,
    private val db: SyncDb,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tzOffsetMin: () -> Int = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000 },
    /** No DATA for this long while a FETCH is open → ask again from the first missing byte. */
    private val chunkStallMs: Long = 3_000,
    /** Re-FETCHes without any progress before giving up on this pass. */
    private val maxStalls: Int = 4,
    private val maxCrcRetries: Int = 2,
    private val log: (String) -> Unit = {},
) {
    fun interface ProgressListener {
        fun onProgress(done: Int, total: Int, fraction: Float)
    }

    suspend fun run(client: OwlClient, address: String, listener: ProgressListener? = null): SyncReport {
        try {
            db.recordStatus(address, client.readStatus(), clock())
            db.recordTimeSync(address, client.timeSync(tzOffsetMin()))
            val entries = client.listAll()
            var fetched = 0
            var acked = 0
            var skipped = 0
            entries.forEachIndexed { i, entry ->
                listener?.onProgress(i, entries.size, 0f)
                when (syncSegment(client, address, entry) { f -> listener?.onProgress(i, entries.size, f) }) {
                    Outcome.FETCHED_AND_ACKED -> { fetched++; acked++ }
                    Outcome.ACKED_EXISTING -> acked++
                    Outcome.SKIPPED -> skipped++
                }
            }
            listener?.onProgress(entries.size, entries.size, 1f)
            val status = client.readStatus().also { db.recordStatus(address, it, clock()) }
            return SyncReport(entries.size, fetched, acked, skipped, status)
        } catch (e: TransportException) {
            throw SyncFailedException(e.message ?: "transport error", e)
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw SyncFailedException("disconnected", e)
        }
    }

    private enum class Outcome { FETCHED_AND_ACKED, ACKED_EXISTING, SKIPPED }

    private suspend fun syncSegment(client: OwlClient, address: String, entry: ListEntry, onFraction: (Float) -> Unit): Outcome {
        val stored = db.storedSegment(address, entry.id)
        if (stored != null && stored.saved && stored.crc32 != null) {
            // Saved earlier but the ACK never made it (app killed, link lost): ACK again.
            return when (client.ack(entry.id, stored.crc32)) {
                ResultCode.OK, ResultCode.NO_SEGMENT -> { db.acked(address, entry.id); Outcome.ACKED_EXISTING }
                ResultCode.CRC_MISMATCH -> {
                    db.reset(address, entry.id)
                    files.discard(address, entry.id)
                    fetchAndAck(client, address, entry, onFraction)
                }
                else -> Outcome.SKIPPED
            }
        }
        return fetchAndAck(client, address, entry, onFraction)
    }

    private suspend fun fetchAndAck(client: OwlClient, address: String, entry: ListEntry, onFraction: (Float) -> Unit): Outcome {
        repeat(maxCrcRetries + 1) {
            db.beginReceiving(address, entry, clock())
            val saved = fetch(client, address, entry, onFraction) ?: return Outcome.SKIPPED
            when (client.ack(entry.id, saved)) {
                ResultCode.OK, ResultCode.NO_SEGMENT -> {
                    db.acked(address, entry.id)
                    return Outcome.FETCHED_AND_ACKED
                }
                ResultCode.CRC_MISMATCH -> {
                    db.reset(address, entry.id)
                    files.discard(address, entry.id)
                }
                else -> return Outcome.SKIPPED
            }
        }
        return Outcome.SKIPPED
    }

    /**
     * Receives [entry] into its `.part` file starting from what is already on disk, then commits it.
     * Returns the CRC32 to ACK with, or null if the segment is malformed (left un-ACKed on the pendant).
     *
     * Chunks are accepted strictly in order ("go-back-N"): a chunk past the first missing byte
     * means one was dropped, so we FETCH again from that byte and ignore the rest of the old stream.
     */
    private suspend fun fetch(client: OwlClient, address: String, entry: ListEntry, onFraction: (Float) -> Unit): Long? {
        val size = entry.size
        var expected = files.partialLength(address, entry.id).coerceAtMost(size)
        var headerSeen = false
        var stalls = 0
        var lastReported = -1L
        drain(client)
        if (expected > 0) log("segment ${entry.id}: resuming at byte $expected of $size")
        client.fetch(entry.id, expected)
        var refetchedAt = expected
        while (expected < size) {
            val chunk = withTimeoutOrNull(chunkStallMs) { client.data.receive() }
            if (chunk == null) {
                if (++stalls > maxStalls) throw SyncFailedException("transfer of ${entry.id} stalled at $expected")
                drain(client)
                client.fetch(entry.id, expected)
                refetchedAt = expected
                continue
            }
            if (chunk.segmentId != entry.id) continue
            val end = chunk.offset + chunk.bytes.size
            when {
                chunk.offset <= expected && end > expected -> {
                    // In order (or overlapping a duplicate): keep the new tail only.
                    val from = (expected - chunk.offset).toInt()
                    val take = (minOf(end, size) - expected).toInt()
                    files.append(address, entry.id, expected, chunk.bytes.copyOfRange(from, from + take))
                    expected += take
                    stalls = 0
                }
                chunk.offset > expected && refetchedAt != expected -> {
                    // Gap: a chunk was lost. Ask again from the first missing byte, once per gap.
                    log("segment ${entry.id}: gap at byte $expected, re-FETCH")
                    client.fetch(entry.id, expected)
                    refetchedAt = expected
                }
                else -> Unit // duplicate, or the old stream still draining after our re-FETCH
            }
            if (!headerSeen && expected >= SegmentHeader.SIZE) {
                headerSeen = true
                try {
                    db.headerReceived(address, entry.id, SegmentHeader.parse(files.readHeaderBytes(address, entry.id, SegmentHeader.SIZE)))
                } catch (_: ProtocolException) {
                    // Validated again after the full transfer.
                }
            }
            if (expected - lastReported >= REPORT_EVERY_BYTES || expected == size) {
                lastReported = expected
                db.progress(address, entry.id, expected)
                onFraction(if (size == 0L) 1f else expected.toFloat() / size)
            }
        }
        return commit(address, entry)
    }

    private suspend fun commit(address: String, entry: ListEntry): Long? {
        val crc = files.crcOfPart(address, entry.id)
        val bytes = files.partFile(address, entry.id).readBytes()
        val header: SegmentHeader
        var events = emptyList<DeviceEvent>()
        var duration: Long? = null
        try {
            header = SegmentHeader.parse(bytes)
            if (header.id != entry.id || SegmentHeader.SIZE + header.payloadLen != entry.size) {
                throw ProtocolException("header does not match LIST entry")
            }
            when (header.type) {
                SegmentType.AUDIO -> duration = Segments.audioDurationMs(bytes)
                SegmentType.EVENTS -> events = Segments.events(bytes)
            }
        } catch (_: ProtocolException) {
            // The bytes are what the pendant has; refetching would give the same. Leave it on
            // the pendant (no ACK) so nothing is lost, and drop our copy.
            files.discard(address, entry.id)
            db.reset(address, entry.id)
            return null
        }
        val file = files.commit(address, entry.id)
        db.saved(address, entry.id, file, crc, header, duration, events)
        log("segment ${entry.id}: saved ${entry.size} bytes, crc %08x, sending ACK".format(crc))
        return crc
    }

    private fun drain(client: OwlClient) {
        while (client.data.tryReceive().isSuccess) Unit
    }

    private companion object {
        const val REPORT_EVERY_BYTES = 8 * 1024L
    }
}
