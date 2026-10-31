package dev.owl.app.protocol

import dev.owl.app.protocol.ProtocolFixtures as F
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun constantsMatchSpec() {
        assertEquals(236, OwlProtocol.DATA_CHUNK_MAX)
        assertEquals(26, OwlProtocol.LIST_MAX_PER_RESPONSE)
        assertEquals("a2690001-8c8b-40f7-a91b-3ffa7ab560b9", OwlProtocol.SERVICE_UUID.toString())
        assertEquals("a2690004-8c8b-40f7-a91b-3ffa7ab560b9", OwlProtocol.DATA_UUID.toString())
    }

    @Test
    fun securityConstantsMatchSpec() {
        // protocol §2.4, same values as tools/owlproto.py (unpair_request(4) == b"\x07\x04")
        assertEquals("0704", Requests.unpair(4).toHex())
        assertEquals(0x10, Status.FLAG_PAIRING_OPEN)
        assertEquals(120_000L, OwlProtocol.PAIRING_WINDOW_MS)
        val s = Status.parse(hex(F.STATUS))
        assertFalse(s.pairingOpen)
        assertTrue(s.copy(flags = s.flags or Status.FLAG_PAIRING_OPEN).pairingOpen)
    }

    @Test
    fun status() {
        val s = Status.parse(hex(F.STATUS))
        assertEquals(1, s.protoVer)
        assertEquals("0.1.2", s.firmwareVersion)
        assertEquals(3987, s.batteryMv)
        assertTrue(s.recordingOn)
        assertTrue(s.storageLow)
        assertFalse(s.storageFull)
        assertFalse(s.charging)
        assertEquals(2150L, s.storageUsedKb)
        assertEquals(16384L, s.storageTotalKb)
        assertEquals(4, s.pendingSegments)
        assertEquals(0x01020304L, s.bootCount)
        assertEquals(F.STATUS, s.pack().toHex())
    }

    @Test
    fun requests() {
        assertEquals(F.TIME_SYNC_REQ, Requests.timeSync(7, 1793440000123, 540).toHex())
        assertEquals(F.LIST_REQ, Requests.listSegments(8, 41, 26).toHex())
        assertEquals(F.FETCH_REQ, Requests.fetchSegment(9, 42, 236L * 3).toHex())
        assertEquals(F.ACK_REQ, Requests.ackSegment(10, 42, 0xDEADBEEFL).toHex())
        assertEquals(F.SET_REC_OFF_REQ, Requests.setRecording(11, false).toHex())
        assertEquals(F.SET_REC_ON_REQ, Requests.setRecording(12, true).toHex())
    }

    @Test
    fun requestParseRoundTrip() {
        val r = Request.parse(hex(F.FETCH_REQ))
        assertEquals(Op.FETCH_SEGMENT, r.op)
        assertEquals(9, r.seq)
        assertEquals(8, r.payload.size)
    }

    @Test
    fun timeSyncResponse() {
        val r = Response.parse(hex(F.TIME_SYNC_RESP))
        assertEquals(Op.TIME_SYNC, r.op)
        assertEquals(7, r.seq)
        assertEquals(ResultCode.OK, r.result)
        assertEquals(TimeSyncReply(12, 3_600_123), TimeSyncReply.parse(r.payload))
        assertEquals(F.TIME_SYNC_RESP, Response(Op.TIME_SYNC, 7, 0, TimeSyncReply(12, 3_600_123).pack()).pack().toHex())
    }

    @Test
    fun listResponseIncludingUnsignedExtremes() {
        val r = Response.parse(hex(F.LIST_RESP))
        val entries = ListEntry.parseAll(r.payload)
        assertEquals(
            listOf(
                ListEntry(42, SegmentType.AUDIO, 98765),
                ListEntry(43, SegmentType.EVENTS, 52),
                ListEntry(0xFFFFFFF0L, SegmentType.AUDIO, 0x80000001L),
            ),
            entries,
        )
        assertEquals(F.LIST_RESP, Response(Op.LIST_SEGMENTS, 8, 0, ListEntry.packAll(entries)).pack().toHex())
    }

    @Test
    fun errorResponse() {
        val r = Response.parse(hex(F.ERR_RESP))
        assertEquals(Op.FETCH_SEGMENT, r.op)
        assertEquals(ResultCode.NO_SEGMENT, r.result)
        assertEquals(0, r.payload.size)
    }

    @Test(expected = ProtocolException::class)
    fun requestIsNotAResponse() {
        Response.parse(hex(F.FETCH_REQ))
    }

    @Test
    fun audioSegment() {
        val seg = hex(F.AUDIO_SEGMENT)
        val h = SegmentHeader.parse(seg)
        assertEquals(SegmentType.AUDIO, h.type)
        assertEquals(Codec.OPUS, h.codec)
        assertEquals(20, h.frameMs)
        assertEquals(42L, h.id)
        assertEquals(12L, h.bootCount)
        assertEquals(3_000_000L, h.startUptimeMs)
        assertEquals(16000, h.sampleRate)
        assertEquals(1, h.channels)
        val frames = Segments.audioFrames(seg)
        assertEquals(5, frames.size)
        frames.forEachIndexed { i, f -> assertArrayEquals(ByteArray(40 + i) { i.toByte() }, f) }
        assertEquals(100L, Segments.audioDurationMs(seg))
        assertEquals(F.AUDIO_SEGMENT_CRC, crc32(seg))
        val rebuilt = Segments.buildAudio(h.copy(payloadLen = 0), frames)
        assertEquals(F.AUDIO_SEGMENT, rebuilt.toHex())
    }

    @Test
    fun dataChunks() {
        val seg = hex(F.AUDIO_SEGMENT)
        val chunks = DataChunk.chunk(42, seg)
        assertEquals(F.AUDIO_CHUNK_COUNT, chunks.size)
        assertEquals(F.AUDIO_CHUNK0, chunks.first().pack().toHex())
        assertEquals(F.AUDIO_CHUNK_LAST, chunks.last().pack().toHex())
        val parsed = DataChunk.parse(hex(F.AUDIO_CHUNK_LAST))
        assertEquals(42L, parsed.segmentId)
        assertEquals(236L, parsed.offset)
    }

    @Test
    fun eventSegment() {
        val seg = hex(F.EVENT_SEGMENT)
        val h = SegmentHeader.parse(seg)
        assertEquals(SegmentType.EVENTS, h.type)
        val events = Segments.events(seg)
        assertEquals(
            listOf(
                DeviceEvent(3_000_500, EventType.BOOKMARK, 0),
                DeviceEvent(3_100_000, EventType.RECORDING_OFF, Cause.APP),
                DeviceEvent(3_200_000, EventType.RECORDING_ON, Cause.BUTTON),
                DeviceEvent(3_300_000, EventType.POWER_OFF, Cause.BATTERY),
            ),
            events,
        )
        assertEquals(F.EVENT_SEGMENT_CRC, crc32(seg))
        assertEquals(F.EVENT_SEGMENT, Segments.buildEvents(h, events).toHex())
    }

    @Test
    fun timeRestore() {
        val syncs = mapOf(12L to (3_600_000L to 1_793_440_000_000L))
        assertEquals(1_793_440_000_000L - 600_000, toUnixMs(12, 3_000_000, syncs))
        assertNull(toUnixMs(11, 3_000_000, syncs))
    }
}
