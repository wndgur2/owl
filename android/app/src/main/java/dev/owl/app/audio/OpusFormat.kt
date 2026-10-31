package dev.owl.app.audio

import dev.owl.app.protocol.Codec
import dev.owl.app.protocol.ProtocolException
import dev.owl.app.protocol.SegmentHeader
import dev.owl.app.protocol.SegmentType
import dev.owl.app.protocol.Segments
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure helpers for feeding raw Owl Opus packets to Android's MediaCodec (no Android imports, so
 * they run in JVM unit tests).
 *
 * Background: an Owl segment stores bare Opus packets, without the Ogg/WebM container a normal
 * `.opus` file has. MediaCodec's Opus decoder still needs the container's setup information, which
 * we pass as "codec specific data" (CSD) buffers:
 * - csd-0: the 19-byte `OpusHead` identification header (RFC 7845 §5.1),
 * - csd-1: codec delay in nanoseconds (u64 LE),
 * - csd-2: seek pre-roll in nanoseconds (u64 LE).
 */
object OpusFormat {
    /** Opus always decodes at 48 kHz internally; OpusHead's pre-skip is counted in 48 kHz samples. */
    const val DECODE_RATE = 48_000

    /**
     * Samples (at 48 kHz) the decoder drops at the start of a stream: the encoder's look-ahead.
     * libopus' look-ahead is 2.5 ms + 4 ms delay compensation = 6.5 ms, i.e. 312 samples at
     * 48 kHz, for every frame size and input rate (at 16 kHz it is 104 input samples, the same
     * 6.5 ms). The firmware uses stock libopus, so 312 is the standard value. If it were off by a
     * little, the only effect is a few ms trimmed or kept at the very start.
     */
    const val PRE_SKIP_48K = 312

    /** [PRE_SKIP_48K] as time: 6.5 ms. */
    const val CODEC_DELAY_NS = PRE_SKIP_48K * 1_000_000_000L / DECODE_RATE

    /**
     * Opus needs ~80 ms of decoded audio before a seek point to converge (RFC 7845 §4.6
     * recommends 80 ms pre-roll). We decode that much before the target and throw it away.
     */
    const val SEEK_PREROLL_MS = 80
    const val SEEK_PREROLL_NS = SEEK_PREROLL_MS * 1_000_000L

    const val OPUS_HEAD_SIZE = 19

    /** RFC 7845 identification header, channel mapping family 0 (mono/stereo). */
    fun opusHead(channels: Int, inputSampleRate: Int, preSkip: Int = PRE_SKIP_48K): ByteArray {
        require(channels in 1..2) { "mapping family 0 supports 1–2 channels" }
        return ByteBuffer.allocate(OPUS_HEAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1) // version
            .put(channels.toByte())
            .putShort(preSkip.toShort())
            .putInt(inputSampleRate) // informational only; the decoder outputs 48 kHz
            .putShort(0) // output gain
            .put(0) // mapping family
            .array()
    }

    fun u64le(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    fun csd1(): ByteArray = u64le(CODEC_DELAY_NS)
    fun csd2(): ByteArray = u64le(SEEK_PREROLL_NS)

    /** Frame that contains [positionMs], clamped to `0..frameCount` (frameCount = end of stream). */
    fun frameIndexAt(positionMs: Long, frameMs: Int, frameCount: Int): Int {
        require(frameMs > 0)
        return (positionMs.coerceAtLeast(0) / frameMs).coerceAtMost(frameCount.toLong()).toInt()
    }

    /** Number of whole frames covering [SEEK_PREROLL_MS]. */
    fun prerollFrames(frameMs: Int): Int = (SEEK_PREROLL_MS + frameMs - 1) / frameMs

    /** Where decoding starts for a seek to [targetFrame]: pre-roll frames earlier, but not before 0. */
    fun decodeStartFrame(targetFrame: Int, frameMs: Int): Int = (targetFrame - prerollFrames(frameMs)).coerceAtLeast(0)

    /** Presentation time we stamp on frame [index] when queueing it into the decoder. */
    fun frameTimeUs(index: Int, frameMs: Int): Long = index.toLong() * frameMs * 1000

    /** Playback position from the frame we started at plus the PCM frames the track has played. */
    fun positionMs(startFrame: Int, frameMs: Int, playedPcmFrames: Long, outputSampleRate: Int): Long =
        startFrame.toLong() * frameMs + playedPcmFrames * 1000 / outputSampleRate
}

/** A parsed audio segment file (protocol §3), ready for the decoder. */
class OpusSegment(val header: SegmentHeader, val frames: List<ByteArray>) {
    val frameMs: Int get() = header.frameMs

    /** Protocol §3.2: frame count × frame_ms. */
    val durationMs: Long get() = frames.size.toLong() * header.frameMs

    companion object {
        /** Reuses the protocol layer's parser; only checks that this is Opus audio we can play. */
        fun parse(bytes: ByteArray): OpusSegment {
            val header = SegmentHeader.parse(bytes)
            if (header.type != SegmentType.AUDIO) throw ProtocolException("not an audio segment")
            if (header.codec != Codec.OPUS) throw ProtocolException("codec ${header.codec} is not Opus")
            if (header.frameMs <= 0) throw ProtocolException("frame_ms is 0")
            if (header.channels !in 1..2) throw ProtocolException("unsupported channel count ${header.channels}")
            return OpusSegment(header, Segments.audioFrames(bytes))
        }

        /**
         * A timeline entry of several segments plays as one recording: their frames back to
         * back. All parts must share frame length, sample rate and channels.
         */
        fun concat(parts: List<OpusSegment>): OpusSegment {
            require(parts.isNotEmpty()) { "no segments" }
            val h = parts.first().header
            for (p in parts) {
                if (p.header.frameMs != h.frameMs || p.header.sampleRate != h.sampleRate || p.header.channels != h.channels) {
                    throw ProtocolException("segment ${p.header.id} has a different audio format")
                }
            }
            return if (parts.size == 1) parts.first() else OpusSegment(h, parts.flatMap { it.frames })
        }
    }
}
