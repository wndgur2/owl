package dev.owl.app.protocol

/**
 * Segment format (protocol §3). The same bytes live on the pendant's flash, travel over BLE and
 * are stored as-is in the phone's segment files.
 */
data class SegmentHeader(
    val type: Int,
    val codec: Int,
    val frameMs: Int,
    val id: Long,
    val bootCount: Long,
    val startUptimeMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val payloadLen: Long,
    val version: Int = OwlProtocol.SEGMENT_VERSION,
) {
    fun pack(): ByteArray = leAlloc(SIZE).apply {
        put(MAGIC)
        put(version.toByte()); put(type.toByte()); put(codec.toByte()); put(frameMs.toByte())
        putInt(id.toInt()); putInt(bootCount.toInt()); putLong(startUptimeMs)
        putShort((sampleRate / 100).toShort()); put(channels.toByte()); put(0)
        putInt(payloadLen.toInt())
    }.array()

    companion object {
        const val SIZE = 32
        val MAGIC = "OWLS".toByteArray(Charsets.US_ASCII)

        fun parse(data: ByteArray): SegmentHeader {
            if (data.size < SIZE) throw ProtocolException("segment shorter than header")
            if (!data.copyOfRange(0, 4).contentEquals(MAGIC)) throw ProtocolException("bad magic")
            val b = le(data, 4, SIZE - 4)
            val ver = b.u8(); val type = b.u8(); val codec = b.u8(); val frameMs = b.u8()
            val id = b.u32(); val boot = b.u32(); val start = b.long
            val sr100 = b.u16(); val ch = b.u8(); b.u8()
            val plen = b.u32()
            return SegmentHeader(type, codec, frameMs, id, boot, start, sr100 * 100, ch, plen, ver)
        }
    }
}

/** A device event from an event-log segment (§3.3). */
data class DeviceEvent(val uptimeMs: Long, val event: Int, val arg: Int) {
    companion object {
        const val SIZE = 10
    }
}

object Segments {
    /** Audio payload: repeated `[len u16][Opus packet]`. Sets [SegmentHeader.payloadLen]. */
    fun buildAudio(header: SegmentHeader, frames: List<ByteArray>): ByteArray {
        val payloadLen = frames.sumOf { 2 + it.size }
        val b = leAlloc(payloadLen)
        frames.forEach { b.putShort(it.size.toShort()).put(it) }
        return header.copy(payloadLen = payloadLen.toLong()).pack() + b.array()
    }

    fun audioFrames(segment: ByteArray): List<ByteArray> {
        val hdr = SegmentHeader.parse(segment)
        val end = SegmentHeader.SIZE + hdr.payloadLen.toInt()
        if (end > segment.size) throw ProtocolException("payload_len beyond segment")
        val frames = ArrayList<ByteArray>()
        var pos = SegmentHeader.SIZE
        while (pos < end) {
            if (pos + 2 > end) throw ProtocolException("truncated frame length")
            val n = (segment[pos].toInt() and 0xFF) or ((segment[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            if (pos + n > end) throw ProtocolException("truncated frame")
            frames += segment.copyOfRange(pos, pos + n)
            pos += n
        }
        return frames
    }

    /** Recording length = frame count × frame_ms (§3.2). */
    fun audioDurationMs(segment: ByteArray): Long {
        val hdr = SegmentHeader.parse(segment)
        return audioFrames(segment).size.toLong() * hdr.frameMs
    }

    fun buildEvents(header: SegmentHeader, events: List<DeviceEvent>): ByteArray {
        val b = leAlloc(events.size * DeviceEvent.SIZE)
        events.forEach { b.putLong(it.uptimeMs).put(it.event.toByte()).put(it.arg.toByte()) }
        return header.copy(payloadLen = (events.size * DeviceEvent.SIZE).toLong()).pack() + b.array()
    }

    fun events(segment: ByteArray): List<DeviceEvent> {
        val hdr = SegmentHeader.parse(segment)
        val len = hdr.payloadLen.toInt()
        if (SegmentHeader.SIZE + len > segment.size) throw ProtocolException("payload_len beyond segment")
        val b = le(segment, SegmentHeader.SIZE, len)
        return List(len / DeviceEvent.SIZE) { DeviceEvent(b.long, b.u8(), b.u8()) }
    }
}

/**
 * Real time from (boot_count, uptime) — protocol §4. [syncs] maps boot_count to
 * (device uptime at sync, phone unix ms at sync). Null = "알 수 없음" (no sync during that boot).
 */
fun toUnixMs(bootCount: Long, uptimeMs: Long, syncs: Map<Long, Pair<Long, Long>>): Long? {
    val (deviceMs, phoneMs) = syncs[bootCount] ?: return null
    return phoneMs + (uptimeMs - deviceMs)
}
