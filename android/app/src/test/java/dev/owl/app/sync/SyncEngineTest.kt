package dev.owl.app.sync

import dev.owl.app.device.DeviceErrorException
import dev.owl.app.device.OwlClient
import dev.owl.app.protocol.ResultCode
import dev.owl.app.device.TimeSyncResult
import dev.owl.app.device.fake.FakeOwlDevice
import dev.owl.app.protocol.DeviceEvent
import dev.owl.app.protocol.EventType
import dev.owl.app.protocol.ListEntry
import dev.owl.app.protocol.Op
import dev.owl.app.protocol.SegmentHeader
import dev.owl.app.protocol.Status
import dev.owl.app.protocol.crc32
import dev.owl.app.protocol.le
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** In-memory [SyncDb] that also checks the save-before-ACK rule. */
class MemorySyncDb : SyncDb {
    data class Row(
        var receivedBytes: Long = 0, var saved: Boolean = false, var crc: Long? = null, var acked: Boolean = false,
        var header: SegmentHeader? = null, var file: File? = null, var durationMs: Long? = null,
    )

    val rows = mutableMapOf<Long, Row>()
    val events = mutableListOf<DeviceEvent>()
    val syncs = mutableListOf<TimeSyncResult>()
    val statuses = mutableListOf<Status>()

    override suspend fun recordStatus(address: String, status: Status, timeMs: Long) { statuses += status }
    override suspend fun recordTimeSync(address: String, sync: TimeSyncResult) { syncs += sync }
    override suspend fun storedSegment(address: String, id: Long) = rows[id]?.let { StoredSegment(it.saved, it.crc, it.acked) }
    override suspend fun beginReceiving(address: String, entry: ListEntry, nowMs: Long) { rows.getOrPut(entry.id) { Row() } }
    override suspend fun headerReceived(address: String, id: Long, header: SegmentHeader) { rows.getValue(id).header = header }
    override suspend fun progress(address: String, id: Long, receivedBytes: Long) { rows.getValue(id).receivedBytes = receivedBytes }
    override suspend fun saved(address: String, id: Long, file: File, crc32: Long, header: SegmentHeader, durationMs: Long?, events: List<DeviceEvent>) {
        assertTrue("file must exist before it is marked saved", file.exists())
        rows.getValue(id).apply { saved = true; crc = crc32; this.file = file; this.header = header; this.durationMs = durationMs }
        this.events += events
    }
    override suspend fun acked(address: String, id: Long) {
        val row = rows.getValue(id)
        assertTrue("ACK before save", row.saved && row.file!!.exists())
        row.acked = true
    }
    override suspend fun reset(address: String, id: Long) { rows[id] = Row() }
}

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val address = "fake"
    private var now = 1_793_440_000_000L

    private fun TestScope.device(scope: CoroutineScope = backgroundScope) =
        FakeOwlDevice(scope, clock = { now }, bootAgoMs = 3_600_000)

    private fun engine(files: SegmentFiles, db: SyncDb) =
        SyncEngine(files, db, clock = { now }, tzOffsetMin = { 540 }, chunkStallMs = 200)

    private suspend fun TestScope.pass(dev: FakeOwlDevice, engine: SyncEngine): SyncReport {
        val client = OwlClient(dev, backgroundScope, requestTimeoutMs = 1_000, clock = { now })
        client.connect()
        try {
            return engine.run(client, address)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun fullSyncSavesThenAcksEverything() = runTest {
        val dev = device()
        val ids = (1..30).map { dev.recordAudio(durationMs = 2_000 + it * 100L, agoMs = 600_000L - it * 10_000) }
        dev.bookmark(agoMs = 1_000)
        val blobs = ids.associateWith { id -> captureBlob(dev, id) }
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()

        val report = pass(dev, engine(files, db))

        assertEquals(31, report.listed) // 30 audio + 1 event log, needs two LIST pages
        assertEquals(31, report.acked)
        assertTrue(dev.storedIds.isEmpty())
        ids.forEach { id ->
            val f = files.finalFile(address, id)
            assertArrayEquals(blobs.getValue(id), f.readBytes())
            assertEquals(crc32(f.readBytes()), db.rows.getValue(id).crc)
        }
        assertEquals(1, db.syncs.size)
        assertEquals(3, db.syncs[0].bootCount)
        assertEquals(EventType.BOOKMARK, db.events.single().event)
        assertEquals(2_100L, db.rows.getValue(ids[0]).durationMs)
        assertEquals(0, report.status!!.pendingSegments)
        // LIST paging: 26, then 5, then an empty page ends it; each page starts after the last id.
        val lists = dev.received.filter { it.op == Op.LIST_SEGMENTS }
        assertEquals(3, lists.size)
        assertEquals(ids[25], le(lists[1].payload).int.toLong())
    }

    @Test
    fun shortListPagesKeepPagingUntilEmpty() = runTest {
        val dev = device()
        dev.listPageMax = 7 // e.g. a smaller MTU
        repeat(10) { dev.recordAudio(durationMs = 1_000) }
        val db = MemorySyncDb()

        val report = pass(dev, engine(SegmentFiles(tmp.newFolder()), db))

        assertEquals(10, report.acked)
        assertEquals(3, dev.received.count { it.op == Op.LIST_SEGMENTS }) // 7, 3, 0
    }

    @Test
    fun droppedChunksAreRefetchedFromFirstGap() = runTest {
        val dev = device()
        val id = dev.recordAudio(durationMs = 10_000)
        val blob = captureBlob(dev, id)
        dev.dropChunkIndices += setOf(3, 4, 9)
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()

        pass(dev, engine(files, db))

        assertArrayEquals(blob, files.finalFile(address, id).readBytes())
        assertTrue(db.rows.getValue(id).acked)
        val fetchOffsets = dev.received.filter { it.op == Op.FETCH_SEGMENT }.map { le(it.payload).let { b -> b.int; b.int.toLong() } }
        assertEquals(0L, fetchOffsets.first())
        assertTrue("re-FETCH from the first missing offset", 3L * 236 in fetchOffsets)
        // ABORT goes out right before each re-FETCH.
        val ops = dev.received.map { it.op }
        val refetch = ops.indexOfLast { it == Op.FETCH_SEGMENT }
        assertEquals(Op.ABORT, ops[refetch - 1])
    }

    @Test
    fun lastChunkDroppedRecoversByStallTimeout() = runTest {
        val dev = device()
        val id = dev.recordAudio(durationMs = 3_000)
        val chunks = (captureBlob(dev, id).size + 235) / 236
        dev.dropChunkIndices += chunks - 1
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()

        pass(dev, engine(files, db))

        assertTrue(db.rows.getValue(id).acked)
    }

    @Test
    fun disconnectMidFetchResumesOnNextPassWithoutLoss() = runTest {
        val dev = device()
        val first = dev.recordAudio(durationMs = 4_000)
        val second = dev.recordAudio(durationMs = 8_000)
        val blob2 = captureBlob(dev, second)
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()
        val eng = engine(files, db)

        // First segment goes through; the link drops after 5 chunks of the second.
        dev.dropChunkIndices.clear()
        var firstDone = false
        val client = OwlClient(dev, backgroundScope, requestTimeoutMs = 1_000, clock = { now })
        client.connect()
        try {
            eng.run(client, address) { done, _, _ ->
                if (done == 1 && !firstDone) { firstDone = true; dev.disconnectAfterChunks = 5 }
            }
            fail("expected the pass to fail")
        } catch (_: SyncFailedException) {
        }
        assertTrue(db.rows.getValue(first).acked)
        assertFalse(db.rows.getValue(second).acked)
        val partial = files.partialLength(address, second)
        assertEquals(5L * 236, partial)
        assertTrue(second in dev.storedIds) // not ACKed → pendant keeps it

        // Next pass: resume the second segment from the first missing byte.
        pass(dev, eng)
        val fetches = dev.received.filter { it.op == Op.FETCH_SEGMENT }
            .map { le(it.payload).let { b -> b.int.toLong() to b.int.toLong() } }
        assertEquals(second to partial, fetches.last())
        assertArrayEquals(blob2, files.finalFile(address, second).readBytes())
        assertTrue(db.rows.getValue(second).acked)
        assertTrue(dev.storedIds.isEmpty())
    }

    @Test
    fun savedButUnackedIsReAckedWithoutRefetch() = runTest {
        val dev = device()
        val id = dev.recordAudio(durationMs = 2_000)
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()
        // Simulate: app saved the file, then died before ACK.
        val blob = captureBlob(dev, id)
        files.append(address, id, 0, blob)
        val f = files.commit(address, id)
        db.rows[id] = MemorySyncDb.Row(blob.size.toLong(), saved = true, crc = crc32(blob), file = f)

        pass(dev, engine(files, db))

        assertTrue(dev.received.none { it.op == Op.FETCH_SEGMENT })
        assertTrue(db.rows.getValue(id).acked)
        assertTrue(dev.storedIds.isEmpty())
    }

    @Test
    fun corruptLocalCopyIsRefetchedAfterCrcMismatch() = runTest {
        val dev = device()
        val id = dev.recordAudio(durationMs = 2_000)
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()
        val blob = captureBlob(dev, id).also { it[40] = (it[40] + 1).toByte() }
        files.append(address, id, 0, blob)
        val f = files.commit(address, id)
        db.rows[id] = MemorySyncDb.Row(blob.size.toLong(), saved = true, crc = crc32(blob), file = f)

        pass(dev, engine(files, db))

        assertTrue(db.rows.getValue(id).acked)
        assertEquals(1, dev.received.count { it.op == Op.FETCH_SEGMENT })
    }

    @Test
    fun malformedSegmentStaysOnPendant() = runTest {
        val dev = device()
        val bad = dev.storeRaw(ByteArray(100) { 7 })
        val good = dev.recordAudio(durationMs = 1_000)
        val files = SegmentFiles(tmp.newFolder())
        val db = MemorySyncDb()

        val report = pass(dev, engine(files, db))

        assertEquals(1, report.skipped)
        assertTrue(bad in dev.storedIds)
        assertFalse(good in dev.storedIds)
    }

    @Test
    fun setRecordingAndTimeSync() = runTest {
        val dev = device()
        val client = OwlClient(dev, backgroundScope, requestTimeoutMs = 1_000, clock = { now })
        client.connect()
        client.setRecording(false)
        assertFalse(dev.recordingOn)
        assertFalse(client.readStatus().recordingOn)
        val ts = client.timeSync(540)
        assertEquals(now, ts.phoneUnixMs)
        assertEquals(3_600_000L, ts.deviceUptimeMs)
        client.disconnect()
        val req = dev.received.first { it.op == Op.SET_RECORDING }
        assertEquals(0, req.payload[0].toInt())
    }

    @Test
    fun deviceEdgeCasesFromTheAgreedSpec() = runTest {
        val dev = device()
        val id = dev.recordAudio(durationMs = 1_000)
        val client = OwlClient(dev, backgroundScope, requestTimeoutMs = 1_000, clock = { now })
        client.connect()
        // A 1-byte request has no seq: the pendant answers seq 0, result 2. (A fresh client's
        // first seq is 0, so the reply matches.)
        val r = client.request(Op.ABORT) { byteArrayOf(Op.ABORT.toByte()) }
        assertEquals(0, r.seq)
        assertEquals(ResultCode.BAD_LENGTH, r.result)
        // SET_RECORDING to the current state: OK, no event.
        client.setRecording(true)
        assertEquals(1, client.listAll().size)
        // FETCH beyond the segment size: result 2.
        val size = client.listAll().single().size
        try {
            client.fetch(id, size + 1)
            fail()
        } catch (e: DeviceErrorException) {
            assertEquals(ResultCode.BAD_LENGTH, e.result)
        }
        client.disconnect()
    }

    @Test
    fun connectFailureSurfaces() = runTest {
        val dev = device()
        dev.failConnects = 1
        val client = OwlClient(dev, backgroundScope)
        try {
            client.connect()
            fail()
        } catch (_: dev.owl.app.device.TransportException) {
        }
        assertNotNull(OwlClient(dev, backgroundScope).apply { connect() })
    }

    /** The exact bytes the fake keeps on "flash" for [id] (via a throwaway connection). */
    private suspend fun TestScope.captureBlob(dev: FakeOwlDevice, id: Long): ByteArray {
        val client = OwlClient(dev, backgroundScope)
        client.connect()
        val size = client.listAll().first { it.id == id }.size.toInt()
        client.fetch(id, 0)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < size) out.write(client.data.receive().bytes)
        client.disconnect()
        dev.received.clear()
        return out.toByteArray()
    }
}
