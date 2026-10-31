package dev.owl.app.device.fake

import dev.owl.app.device.Characteristic
import dev.owl.app.device.OwlTransport
import dev.owl.app.device.TransportEvent
import dev.owl.app.device.TransportException
import dev.owl.app.protocol.Cause
import dev.owl.app.protocol.Codec
import dev.owl.app.protocol.ConfigKey
import dev.owl.app.protocol.DataChunk
import dev.owl.app.protocol.DeviceEvent
import dev.owl.app.protocol.EventType
import dev.owl.app.protocol.ListEntry
import dev.owl.app.protocol.Op
import dev.owl.app.protocol.OwlProtocol
import dev.owl.app.protocol.Request
import dev.owl.app.protocol.Response
import dev.owl.app.protocol.ResultCode
import dev.owl.app.protocol.SegmentHeader
import dev.owl.app.protocol.SegmentType
import dev.owl.app.protocol.Segments
import dev.owl.app.protocol.Status
import dev.owl.app.protocol.TimeSyncReply
import dev.owl.app.protocol.crc32
import dev.owl.app.protocol.le
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap
import kotlin.random.Random

/** Produces Opus packets for [durationMs] of synthetic audio (one packet per frame). */
fun interface FrameSource {
    fun frames(durationMs: Long, frameMs: Int): List<ByteArray>
}

/** Random bytes shaped like 16 kbps / 20 ms Opus packets (40 bytes). Not decodable; for tests. */
class RandomFrameSource(private val random: Random = Random(1)) : FrameSource {
    override fun frames(durationMs: Long, frameMs: Int): List<ByteArray> =
        List((durationMs / frameMs).toInt()) { random.nextBytes(38 + random.nextInt(5)) }
}

/**
 * An in-memory pendant that speaks protocol v1 over the [OwlTransport] seam, playing the
 * firmware's part: segments with ids, (boot_count, uptime) timestamps, LIST paging, FETCH from an
 * offset, ACK with CRC check and delete, SET_RECORDING with events, STATUS.
 *
 * Fault injection for tests and the debug build: refuse connections, drop DATA chunks, drop the
 * link in the middle of a FETCH.
 *
 * State survives connect/disconnect like flash does; [reboot] starts a new boot (uptime from 0).
 */
class FakeOwlDevice(
    private val scope: CoroutineScope,
    private val frameSource: FrameSource = RandomFrameSource(),
    /** Wall clock in ms; tests pass a fake one. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Delay between DATA notifications, to make progress visible in the UI. 0 in tests. */
    private val chunkIntervalMs: Long = 0,
    initialBootCount: Long = 3,
    /** How long ago the current boot started (so history can be seeded in the past). */
    bootAgoMs: Long = 0,
    /** Segment ids continue across reboots (and across app restarts in the debug build). */
    initialNextId: Long = 1,
) : OwlTransport {
    private val lock = Mutex()
    private val events_ = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = events_.receiveAsFlow()
    override val mtu: Int = OwlProtocol.ATT_MTU

    // --- "flash" -----------------------------------------------------------
    var bootCount: Long = initialBootCount
        private set
    private var bootStartMs: Long = clock() - bootAgoMs
    private var nextId: Long = initialNextId
    private val stored = TreeMap<Long, ByteArray>()
    private val pendingEvents = ArrayList<DeviceEvent>()
    var recordingOn: Boolean = true
        private set
    var batteryMv: Int = 3_950
    val storageTotalKb: Long = 16 * 1024
    var firmware: Triple<Int, Int, Int> = Triple(0, 1, 0)

    /** Segments the pendant still holds (not yet ACKed). */
    val storedIds: Set<Long> get() = stored.keys.toSet()
    val ackedIds = mutableListOf<Long>()

    // --- faults ------------------------------------------------------------
    /** Next N connect() calls fail. */
    var failConnects: Int = 0

    /** Drop every DATA chunk whose index (within one FETCH stream) is in this set, once each. */
    val dropChunkIndices = mutableSetOf<Int>()

    /** Drop the link after this many DATA chunks of the next FETCH (one-shot). */
    var disconnectAfterChunks: Int? = null

    /** Entries per LIST page; a real pendant may send fewer than 26 to fit a smaller MTU. */
    var listPageMax: Int = OwlProtocol.LIST_MAX_PER_RESPONSE

    /** Chance per chunk to drop it (debug-build "flaky radio"). */
    var dropProbability: Double = 0.0
    private val random = Random(7)

    // --- link --------------------------------------------------------------
    var connected: Boolean = false
        private set
    private var stream: Job? = null
    val received = mutableListOf<Request>()

    fun uptimeMs(): Long = clock() - bootStartMs

    override suspend fun connect() {
        lock.withLock {
            if (failConnects > 0) {
                failConnects--
                throw TransportException("fake: device not reachable")
            }
            if (connected) return
            // Leftovers of the previous connection (e.g. its Disconnected) must not reach the new client.
            while (events_.tryReceive().isSuccess) Unit
            connected = true
            // The open event log is sealed into a listable segment when a central connects.
            flushEvents()
        }
    }

    override suspend fun disconnect() {
        lock.withLock { dropLink() }
    }

    private fun dropLink() {
        if (!connected) return
        connected = false
        stream?.cancel()
        events_.trySend(TransportEvent.Disconnected)
    }

    override suspend fun readStatus(): ByteArray = lock.withLock {
        if (!connected) throw TransportException("fake: not connected")
        status().pack()
    }

    fun status(): Status {
        val usedKb = stored.values.sumOf { it.size.toLong() } / 1024
        var flags = 0
        if (recordingOn) flags = flags or Status.FLAG_RECORDING
        if (usedKb * 10 >= storageTotalKb * 9) flags = flags or Status.FLAG_STORAGE_LOW
        if (usedKb >= storageTotalKb) flags = flags or Status.FLAG_STORAGE_FULL
        return Status(
            OwlProtocol.PROTO_VER, firmware.first, firmware.second, firmware.third, batteryMv, flags,
            usedKb, storageTotalKb, stored.size, bootCount,
        )
    }

    override suspend fun writeControl(bytes: ByteArray) {
        lock.withLock {
            if (!connected) throw TransportException("fake: not connected")
            if (bytes.size < 2) {
                // No seq to echo: answer seq 0, BAD_LENGTH.
                val op = if (bytes.isEmpty()) 0 else bytes[0].toInt() and 0x7F
                events_.trySend(TransportEvent.Notification(Characteristic.CONTROL, Response(op, 0, ResultCode.BAD_LENGTH).pack()))
                return
            }
            val req = Request.parse(bytes)
            received += req
            val resp = handle(req)
            events_.trySend(TransportEvent.Notification(Characteristic.CONTROL, resp.pack()))
        }
    }

    private fun handle(req: Request): Response {
        fun reply(result: Int, payload: ByteArray = ByteArray(0)) = Response(req.op, req.seq, result, payload)
        val p = le(req.payload)
        return when (req.op) {
            Op.TIME_SYNC -> if (req.payload.size != 10) reply(ResultCode.BAD_LENGTH)
            else reply(ResultCode.OK, TimeSyncReply(bootCount, uptimeMs()).pack())

            Op.LIST_SEGMENTS -> {
                if (req.payload.size != 5) return reply(ResultCode.BAD_LENGTH)
                val after = p.int.toLong() and 0xFFFFFFFFL
                val max = (p.get().toInt() and 0xFF).coerceAtMost(listPageMax)
                val page = stored.tailMap(after, false).entries.take(max)
                    .map { (id, blob) -> ListEntry(id, blob[5].toInt(), blob.size.toLong()) }
                reply(ResultCode.OK, ListEntry.packAll(page))
            }

            Op.FETCH_SEGMENT -> {
                if (req.payload.size != 8) return reply(ResultCode.BAD_LENGTH)
                val id = p.int.toLong() and 0xFFFFFFFFL
                val offset = p.int.toLong() and 0xFFFFFFFFL
                val blob = stored[id] ?: return reply(ResultCode.NO_SEGMENT)
                if (offset > blob.size) return reply(ResultCode.BAD_LENGTH)
                // A new FETCH replaces a running one.
                stream?.cancel()
                stream = scope.launch { streamChunks(id, blob, offset.toInt()) }
                reply(ResultCode.OK)
            }

            Op.ACK_SEGMENT -> {
                if (req.payload.size != 8) return reply(ResultCode.BAD_LENGTH)
                val id = p.int.toLong() and 0xFFFFFFFFL
                val crc = p.int.toLong() and 0xFFFFFFFFL
                val blob = stored[id] ?: return reply(ResultCode.NO_SEGMENT)
                if (crc32(blob) != crc) return reply(ResultCode.CRC_MISMATCH)
                stored.remove(id)
                ackedIds += id
                reply(ResultCode.OK)
            }

            Op.ABORT -> {
                stream?.cancel()
                reply(ResultCode.OK)
            }

            Op.SET_RECORDING -> {
                if (req.payload.size != 1) return reply(ResultCode.BAD_LENGTH)
                setRecording(req.payload[0].toInt() != 0, Cause.APP)
                reply(ResultCode.OK)
            }

            Op.SET_CONFIG -> {
                if (req.payload.size != 5) return reply(ResultCode.BAD_LENGTH)
                val key = p.get().toInt() and 0xFF
                val value = p.int.toLong() and 0xFFFFFFFFL
                // Illustrative ranges for the fake only; the firmware defines the real ones.
                val valid = when (key) {
                    ConfigKey.OPUS_BITRATE -> value in 6_000..32_000
                    ConfigKey.VAD_SENSITIVITY -> value in 0..3
                    ConfigKey.SEGMENT_MAX_SECONDS -> value in 10..3_600
                    else -> false
                }
                reply(if (valid) ResultCode.OK else ResultCode.BAD_LENGTH)
            }
            else -> reply(ResultCode.UNKNOWN_OP)
        }
    }

    private suspend fun streamChunks(id: Long, blob: ByteArray, start: Int) {
        val chunks = DataChunk.chunk(id, blob, start)
        chunks.forEachIndexed { index, chunk ->
            if (chunkIntervalMs > 0) delay(chunkIntervalMs) else kotlinx.coroutines.yield()
            val cutAfter = disconnectAfterChunks
            if (cutAfter != null && index >= cutAfter) {
                disconnectAfterChunks = null
                lock.withLock { dropLink() }
                return
            }
            if (dropChunkIndices.remove(index)) return@forEachIndexed
            if (dropProbability > 0 && random.nextDouble() < dropProbability) return@forEachIndexed
            if (!connected) return
            events_.trySend(TransportEvent.Notification(Characteristic.DATA, chunk.pack()))
        }
    }

    // --- things that happen on the pendant ----------------------------------

    /** Records an audio segment that started [agoMs] before now in the current boot. */
    fun recordAudio(durationMs: Long, agoMs: Long = durationMs, frameMs: Int = 20): Long {
        val id = nextId++
        val header = SegmentHeader(
            type = SegmentType.AUDIO, codec = Codec.OPUS, frameMs = frameMs, id = id, bootCount = bootCount,
            startUptimeMs = (uptimeMs() - agoMs).coerceAtLeast(0), sampleRate = 16_000, channels = 1, payloadLen = 0,
        )
        stored[id] = Segments.buildAudio(header, frameSource.frames(durationMs, frameMs))
        return id
    }

    /** Button short press (D7). Written to the event log right away (decisions.md #19). */
    fun bookmark(agoMs: Long = 0) {
        pendingEvents += DeviceEvent((uptimeMs() - agoMs).coerceAtLeast(0), EventType.BOOKMARK, 0)
    }

    fun setRecording(on: Boolean, cause: Int = Cause.BUTTON) {
        if (recordingOn == on) return // same state → OK, no event
        recordingOn = on
        pendingEvents += DeviceEvent(uptimeMs(), if (on) EventType.RECORDING_ON else EventType.RECORDING_OFF, cause)
    }

    /**
     * Power cycle [agoMs] before now: new boot_count, uptime restarts at 0. Pending events of the
     * old boot are flushed first.
     */
    fun reboot(agoMs: Long = 0) {
        flushEvents()
        bootCount++
        bootStartMs = clock() - agoMs
        pendingEvents += DeviceEvent(0, EventType.POWER_ON, Cause.OTHER)
    }

    /** Closes the current event log into a segment (the firmware does this per file). */
    private fun flushEvents() {
        if (pendingEvents.isEmpty()) return
        val id = nextId++
        val header = SegmentHeader(
            type = SegmentType.EVENTS, codec = Codec.PCM16, frameMs = 0, id = id, bootCount = bootCount,
            startUptimeMs = pendingEvents.first().uptimeMs, sampleRate = 16_000, channels = 1, payloadLen = 0,
        )
        stored[id] = Segments.buildEvents(header, pendingEvents.toList())
        pendingEvents.clear()
    }

    /** Puts raw bytes on "flash" (tests: malformed segments). */
    fun storeRaw(blob: ByteArray): Long {
        val id = nextId++
        stored[id] = blob
        return id
    }
}
