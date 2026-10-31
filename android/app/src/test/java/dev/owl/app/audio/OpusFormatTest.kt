package dev.owl.app.audio

import dev.owl.app.protocol.Codec
import dev.owl.app.protocol.ProtocolException
import dev.owl.app.protocol.SegmentHeader
import dev.owl.app.protocol.SegmentType
import dev.owl.app.protocol.Segments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpusFormatTest {

    @Test
    fun opusHeadLayout() {
        val head = OpusFormat.opusHead(channels = 1, inputSampleRate = 16_000)
        // "OpusHead", v1, 1 ch, pre-skip 312 (0x0138), 16000 Hz (0x00003E80), gain 0, family 0
        val expected = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            1, 1, 0x38, 0x01, 0x80.toByte(), 0x3E, 0, 0, 0, 0, 0,
        )
        assertEquals(19, head.size)
        assertArrayEquals(expected, head)
    }

    @Test
    fun opusHeadRejectsMultichannel() {
        assertThrows(IllegalArgumentException::class.java) { OpusFormat.opusHead(3, 16_000) }
    }

    @Test
    fun csdNanoseconds() {
        assertEquals(6_500_000L, OpusFormat.CODEC_DELAY_NS)
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x2E, 0x63, 0, 0, 0, 0, 0), OpusFormat.csd1())
        // 80_000_000 = 0x04C4B400
        assertArrayEquals(byteArrayOf(0, 0xB4.toByte(), 0xC4.toByte(), 0x04, 0, 0, 0, 0), OpusFormat.csd2())
    }

    @Test
    fun frameIndexing() {
        assertEquals(0, OpusFormat.frameIndexAt(0, 20, 100))
        assertEquals(0, OpusFormat.frameIndexAt(19, 20, 100))
        assertEquals(1, OpusFormat.frameIndexAt(20, 20, 100))
        assertEquals(50, OpusFormat.frameIndexAt(1_000, 20, 100))
        assertEquals(100, OpusFormat.frameIndexAt(10_000, 20, 100)) // past the end → end of stream
        assertEquals(0, OpusFormat.frameIndexAt(-5, 20, 100))
    }

    @Test
    fun prerollBeforeSeekTarget() {
        assertEquals(4, OpusFormat.prerollFrames(20)) // 80 ms / 20 ms
        assertEquals(2, OpusFormat.prerollFrames(60)) // 120 ms ≥ 80 ms
        assertEquals(46, OpusFormat.decodeStartFrame(50, 20))
        assertEquals(0, OpusFormat.decodeStartFrame(2, 20))
        assertEquals(1_000_000L, OpusFormat.frameTimeUs(50, 20))
    }

    @Test
    fun positionFromPlayedPcm() {
        // Started at frame 50 (1.0 s), played 24000 samples at 48 kHz (0.5 s).
        assertEquals(1_500L, OpusFormat.positionMs(50, 20, 24_000, 48_000))
        assertEquals(0L, OpusFormat.positionMs(0, 20, 0, 48_000))
    }

    @Test
    fun segmentParse() {
        val frames = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4), ByteArray(40))
        val seg = OpusSegment.parse(Segments.buildAudio(header(), frames))
        assertEquals(3, seg.frames.size)
        assertArrayEquals(frames[2], seg.frames[2])
        assertEquals(60L, seg.durationMs)
        assertEquals(16_000, seg.header.sampleRate)
    }

    @Test
    fun segmentRejectsNonOpus() {
        val pcm = Segments.buildAudio(header().copy(codec = Codec.PCM16), listOf(byteArrayOf(1)))
        assertThrows(ProtocolException::class.java) { OpusSegment.parse(pcm) }
        val events = Segments.buildEvents(header().copy(type = SegmentType.EVENTS, codec = 0), emptyList())
        assertThrows(ProtocolException::class.java) { OpusSegment.parse(events) }
    }

    private fun header() = SegmentHeader(
        type = SegmentType.AUDIO,
        codec = Codec.OPUS,
        frameMs = 20,
        id = 7,
        bootCount = 1,
        startUptimeMs = 0,
        sampleRate = 16_000,
        channels = 1,
        payloadLen = 0,
    )
}
