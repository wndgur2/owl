package dev.owl.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Owl BLE protocol v1 — `protocol/ble-protocol.md` is the contract, `tools/owlproto.py` the
 * reference implementation. Every layout here must stay byte-exact with both; the fixtures in
 * `ProtocolTest` come from owlproto.py.
 *
 * Pure Kotlin/JVM: no Android imports, so it runs in plain unit tests.
 * All integers are little-endian.
 */
object OwlProtocol {
    const val PROTO_VER = 1
    const val SEGMENT_VERSION = 1

    private fun uuid(short: Int) = UUID.fromString("a269%04x-8c8b-40f7-a91b-3ffa7ab560b9".format(short))

    val SERVICE_UUID: UUID = uuid(0x0001)
    val STATUS_UUID: UUID = uuid(0x0002)
    val CONTROL_UUID: UUID = uuid(0x0003)
    val DATA_UUID: UUID = uuid(0x0004)

    /** Client Characteristic Configuration Descriptor, needed to enable notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised name prefix: `Owl-XXXX`. */
    const val NAME_PREFIX = "Owl-"

    const val ATT_MTU = 247
    const val ATT_PAYLOAD = ATT_MTU - 3 // 244
    const val DATA_HEADER_SIZE = 8
    const val DATA_CHUNK_MAX = ATT_PAYLOAD - DATA_HEADER_SIZE // 236

    /** (244 − 4) / 9 = 26 entries per LIST_SEGMENTS response. */
    const val LIST_MAX_PER_RESPONSE = (ATT_PAYLOAD - 3 - 1) / ListEntry.SIZE
}

object Op {
    const val TIME_SYNC = 0x01
    const val LIST_SEGMENTS = 0x02
    const val FETCH_SEGMENT = 0x03
    const val ACK_SEGMENT = 0x04
    const val ABORT = 0x05
    const val SET_RECORDING = 0x06
    const val SET_CONFIG = 0x10
    const val RESPONSE_BIT = 0x80
}

object ResultCode {
    const val OK = 0
    const val UNKNOWN_OP = 1
    const val BAD_LENGTH = 2
    const val NO_SEGMENT = 3
    const val CRC_MISMATCH = 4
    const val BUSY = 5
}

object SegmentType {
    const val AUDIO = 1
    const val EVENTS = 2
}

object Codec {
    const val PCM16 = 0
    const val OPUS = 1
}

/** Event codes in an event-log segment (protocol §3.3). */
object EventType {
    const val BOOKMARK = 1
    const val RECORDING_OFF = 2
    const val RECORDING_ON = 3
    const val STORAGE_FULL = 4
    const val BATTERY_LOW = 5
    const val POWER_OFF = 6
    const val POWER_ON = 7
}

object Cause {
    const val BUTTON = 0
    const val APP = 1
    const val BATTERY = 2
    const val CHARGED = 3
    const val OTHER = 4
}

object ConfigKey {
    const val OPUS_BITRATE = 1
    const val VAD_SENSITIVITY = 2
    const val SEGMENT_MAX_SECONDS = 3
}

class ProtocolException(message: String) : Exception(message)

internal fun le(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteBuffer =
    ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)

internal fun leAlloc(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

internal fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
internal fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
internal fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

/** CRC-32 (IEEE, same as Python's `zlib.crc32`) as an unsigned value. */
fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long =
    CRC32().apply { update(bytes, offset, length) }.value

// --- STATUS (§2.1) -----------------------------------------------------------

data class Status(
    val protoVer: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val fwPatch: Int,
    val batteryMv: Int,
    val flags: Int,
    val storageUsedKb: Long,
    val storageTotalKb: Long,
    val pendingSegments: Int,
    val bootCount: Long,
) {
    /** Always false for now: the board has no charge-status pin (PRD 10 H). */
    val charging: Boolean get() = flags and FLAG_CHARGING != 0
    val recordingOn: Boolean get() = flags and FLAG_RECORDING != 0
    val storageLow: Boolean get() = flags and FLAG_STORAGE_LOW != 0
    val storageFull: Boolean get() = flags and FLAG_STORAGE_FULL != 0
    val firmwareVersion: String get() = "$fwMajor.$fwMinor.$fwPatch"

    fun pack(): ByteArray = leAlloc(SIZE).apply {
        put(protoVer.toByte()); put(fwMajor.toByte()); put(fwMinor.toByte()); put(fwPatch.toByte())
        putShort(batteryMv.toShort()); put(flags.toByte()); put(0)
        putInt(storageUsedKb.toInt()); putInt(storageTotalKb.toInt())
        putShort(pendingSegments.toShort()); putShort(0)
        putInt(bootCount.toInt())
    }.array()

    companion object {
        const val SIZE = 24
        const val FLAG_CHARGING = 1 shl 0
        const val FLAG_RECORDING = 1 shl 1
        const val FLAG_STORAGE_LOW = 1 shl 2
        const val FLAG_STORAGE_FULL = 1 shl 3

        fun parse(data: ByteArray): Status {
            if (data.size < SIZE) throw ProtocolException("STATUS too short: ${data.size}")
            val b = le(data)
            val ver = b.u8(); val ma = b.u8(); val mi = b.u8(); val pa = b.u8()
            val mv = b.u16(); val flags = b.u8(); b.u8()
            val used = b.u32(); val total = b.u32()
            val pending = b.u16(); b.u16()
            val boot = b.u32()
            return Status(ver, ma, mi, pa, mv, flags, used, total, pending, boot)
        }
    }
}

// --- CONTROL (§2.2) ----------------------------------------------------------

/** Request builders: `[op u8][seq u8][payload]`. */
object Requests {
    fun request(op: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(op.toByte(), (seq and 0xFF).toByte()) + payload

    fun timeSync(seq: Int, unixMs: Long, tzOffsetMin: Int): ByteArray =
        request(Op.TIME_SYNC, seq, leAlloc(10).putLong(unixMs).putShort(tzOffsetMin.toShort()).array())

    fun listSegments(seq: Int, afterId: Long, max: Int = OwlProtocol.LIST_MAX_PER_RESPONSE): ByteArray =
        request(Op.LIST_SEGMENTS, seq, leAlloc(5).putInt(afterId.toInt()).put(max.toByte()).array())

    fun fetchSegment(seq: Int, id: Long, offset: Long = 0): ByteArray =
        request(Op.FETCH_SEGMENT, seq, leAlloc(8).putInt(id.toInt()).putInt(offset.toInt()).array())

    fun ackSegment(seq: Int, id: Long, crc32: Long): ByteArray =
        request(Op.ACK_SEGMENT, seq, leAlloc(8).putInt(id.toInt()).putInt(crc32.toInt()).array())

    fun abort(seq: Int): ByteArray = request(Op.ABORT, seq)

    fun setRecording(seq: Int, on: Boolean): ByteArray = request(Op.SET_RECORDING, seq, byteArrayOf(if (on) 1 else 0))

    fun setConfig(seq: Int, key: Int, value: Long): ByteArray =
        request(Op.SET_CONFIG, seq, leAlloc(5).put(key.toByte()).putInt(value.toInt()).array())
}

/** A parsed request (used by the fake device, which plays the firmware's role). */
class Request(val op: Int, val seq: Int, val payload: ByteArray) {
    companion object {
        fun parse(data: ByteArray): Request {
            if (data.size < 2) throw ProtocolException("request too short")
            return Request(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data.copyOfRange(2, data.size))
        }
    }
}

/** `[op | 0x80][seq u8][result u8][payload]`. [op] is stored without the response bit. */
class Response(val op: Int, val seq: Int, val result: Int, val payload: ByteArray = ByteArray(0)) {
    fun pack(): ByteArray =
        byteArrayOf((op or Op.RESPONSE_BIT).toByte(), seq.toByte(), result.toByte()) + payload

    companion object {
        fun parse(data: ByteArray): Response {
            if (data.size < 3 || data[0].toInt() and Op.RESPONSE_BIT == 0) {
                throw ProtocolException("not a CONTROL response")
            }
            return Response(
                op = data[0].toInt() and 0x7F,
                seq = data[1].toInt() and 0xFF,
                result = data[2].toInt() and 0xFF,
                payload = data.copyOfRange(3, data.size),
            )
        }
    }
}

data class TimeSyncReply(val bootCount: Long, val uptimeMs: Long) {
    fun pack(): ByteArray = leAlloc(12).putInt(bootCount.toInt()).putLong(uptimeMs).array()

    companion object {
        fun parse(payload: ByteArray): TimeSyncReply {
            if (payload.size < 12) throw ProtocolException("TIME_SYNC reply too short")
            val b = le(payload)
            return TimeSyncReply(b.u32(), b.long)
        }
    }
}

/** One LIST_SEGMENTS entry. [size] is the whole segment in bytes (32-byte header + payload). */
data class ListEntry(val id: Long, val type: Int, val size: Long) {
    companion object {
        const val SIZE = 9

        fun parseAll(payload: ByteArray): List<ListEntry> {
            if (payload.isEmpty()) throw ProtocolException("LIST reply empty")
            val count = payload[0].toInt() and 0xFF
            if (payload.size < 1 + count * SIZE) throw ProtocolException("LIST reply truncated")
            val b = le(payload, 1, count * SIZE)
            return List(count) { ListEntry(b.u32(), b.u8(), b.u32()) }
        }

        fun packAll(entries: List<ListEntry>): ByteArray {
            if (entries.size > OwlProtocol.LIST_MAX_PER_RESPONSE) throw ProtocolException("too many entries")
            val b = leAlloc(1 + entries.size * SIZE).put(entries.size.toByte())
            entries.forEach { b.putInt(it.id.toInt()).put(it.type.toByte()).putInt(it.size.toInt()) }
            return b.array()
        }
    }
}

// --- DATA (§2.3) -------------------------------------------------------------

/** One DATA notification: `[segment_id u32][offset u32][bytes]`. */
class DataChunk(val segmentId: Long, val offset: Long, val bytes: ByteArray) {
    fun pack(): ByteArray =
        leAlloc(OwlProtocol.DATA_HEADER_SIZE).putInt(segmentId.toInt()).putInt(offset.toInt()).array() + bytes

    companion object {
        fun parse(data: ByteArray): DataChunk {
            if (data.size < OwlProtocol.DATA_HEADER_SIZE) throw ProtocolException("DATA too short")
            val b = le(data)
            return DataChunk(b.u32(), b.u32(), data.copyOfRange(OwlProtocol.DATA_HEADER_SIZE, data.size))
        }

        /** Splits [blob] the way the firmware does, starting at [start]. */
        fun chunk(segmentId: Long, blob: ByteArray, start: Int = 0, max: Int = OwlProtocol.DATA_CHUNK_MAX): List<DataChunk> =
            (start until blob.size step max).map { off ->
                DataChunk(segmentId, off.toLong(), blob.copyOfRange(off, minOf(off + max, blob.size)))
            }
    }
}
