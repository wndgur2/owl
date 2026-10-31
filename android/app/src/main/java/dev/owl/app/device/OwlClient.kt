package dev.owl.app.device

import dev.owl.app.protocol.DataChunk
import dev.owl.app.protocol.ListEntry
import dev.owl.app.protocol.Op
import dev.owl.app.protocol.OwlProtocol
import dev.owl.app.protocol.ProtocolException
import dev.owl.app.protocol.Requests
import dev.owl.app.protocol.Response
import dev.owl.app.protocol.ResultCode
import dev.owl.app.protocol.Status
import dev.owl.app.protocol.TimeSyncReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** The pendant answered a request with a non-OK result code. */
class DeviceErrorException(val op: Int, val result: Int) : Exception("op 0x%02x failed with result %d".format(op, result))

/**
 * Protocol client for one connection (protocol §2.2). Matches CONTROL responses to requests by
 * `seq`, applies timeouts, and hands DATA chunks to the sync engine through [data].
 *
 * Create a new client per connection: after a disconnect its channels are closed.
 */
class OwlClient(
    private val transport: OwlTransport,
    private val scope: CoroutineScope,
    private val requestTimeoutMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val responses = Channel<Response>(Channel.UNLIMITED)

    /** DATA notifications, in arrival order. */
    val data = Channel<DataChunk>(Channel.UNLIMITED)

    private val _status = MutableStateFlow<Status?>(null)

    /** Latest STATUS (read or notified). */
    val status: StateFlow<Status?> = _status

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val requestLock = Mutex()
    private var seq = 0
    private var collector: Job? = null

    suspend fun connect() {
        // Collect before connecting so no notification is missed.
        // UNDISPATCHED: subscribe right now, before connect() can produce anything.
        // The collector ends at the first Disconnected so it never steals the next connection's events.
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.first { event ->
                when (event) {
                    is TransportEvent.Notification -> onNotification(event)
                    TransportEvent.Disconnected -> {
                        _connected.value = false
                        val cause = TransportException("disconnected")
                        responses.close(cause)
                        data.close(cause)
                    }
                }
                event is TransportEvent.Disconnected
            }
        }
        try {
            transport.connect()
        } catch (e: Exception) {
            collector?.cancel()
            throw e
        }
        _connected.value = true
    }

    private fun onNotification(n: TransportEvent.Notification) {
        try {
            when (n.characteristic) {
                Characteristic.CONTROL -> responses.trySend(Response.parse(n.value))
                Characteristic.DATA -> data.trySend(DataChunk.parse(n.value))
                Characteristic.STATUS -> _status.value = Status.parse(n.value)
            }
        } catch (_: ProtocolException) {
            // Malformed notification: ignore it; timeouts and CRC catch real problems.
        }
    }

    suspend fun disconnect() {
        try {
            transport.disconnect()
        } finally {
            _connected.value = false
            collector?.cancel()
            responses.close()
            data.close()
        }
    }

    suspend fun readStatus(): Status = Status.parse(transport.readStatus()).also { _status.value = it }

    /** Sends one request and waits for the response with the same op and seq. */
    suspend fun request(op: Int, build: (seq: Int) -> ByteArray): Response = requestLock.withLock {
        val s = seq
        seq = (seq + 1) and 0xFF
        // Drop late responses to earlier, timed-out requests.
        while (responses.tryReceive().isSuccess) Unit
        transport.writeControl(build(s))
        try {
            withTimeout(requestTimeoutMs) {
                var r: Response
                do {
                    r = responses.receive()
                } while (r.seq != s || r.op != op)
                r
            }
        } catch (e: TimeoutCancellationException) {
            throw TransportException("no response to op 0x%02x".format(op), e)
        }
    }

    private suspend fun requestOk(op: Int, build: (Int) -> ByteArray): Response {
        val r = request(op, build)
        if (r.result != ResultCode.OK) throw DeviceErrorException(op, r.result)
        return r
    }

    /**
     * TIME_SYNC. The phone time paired with the device uptime is the midpoint between sending
     * the request and getting the reply, which halves the radio delay error.
     */
    suspend fun timeSync(tzOffsetMin: Int): TimeSyncResult {
        val sent = clock()
        val r = requestOk(Op.TIME_SYNC) { Requests.timeSync(it, sent, tzOffsetMin) }
        val received = clock()
        val reply = TimeSyncReply.parse(r.payload)
        return TimeSyncResult(reply.bootCount, reply.uptimeMs, sent + (received - sent) / 2)
    }

    /**
     * All pending segments (protocol §2.2). There is no "more" flag and a page may hold fewer
     * than 26 entries (the device fits it to the MTU), so keep asking with the last id until an
     * empty page comes back.
     */
    suspend fun listAll(): List<ListEntry> {
        val all = ArrayList<ListEntry>()
        var after = 0L
        while (true) {
            val r = requestOk(Op.LIST_SEGMENTS) { Requests.listSegments(it, after) }
            val page = ListEntry.parseAll(r.payload)
            if (page.isEmpty()) return all
            all += page
            after = page.last().id
        }
    }

    /**
     * Starts streaming [id] from [offset]; chunks arrive on [data]. A new FETCH replaces a running
     * stream; BUSY is still handled (abort, then ask again) in case firmware ever answers that way.
     */
    suspend fun fetch(id: Long, offset: Long) {
        val r = request(Op.FETCH_SEGMENT) { Requests.fetchSegment(it, id, offset) }
        when (r.result) {
            ResultCode.OK -> return
            ResultCode.BUSY -> {
                abort()
                requestOk(Op.FETCH_SEGMENT) { Requests.fetchSegment(it, id, offset) }
            }
            else -> throw DeviceErrorException(Op.FETCH_SEGMENT, r.result)
        }
    }

    /** Returns the result code; the caller decides what CRC_MISMATCH / NO_SEGMENT mean. */
    suspend fun ack(id: Long, crc32: Long): Int = request(Op.ACK_SEGMENT) { Requests.ackSegment(it, id, crc32) }.result

    suspend fun abort() {
        request(Op.ABORT) { Requests.abort(it) }
    }

    suspend fun setRecording(on: Boolean) {
        requestOk(Op.SET_RECORDING) { Requests.setRecording(it, on) }
    }
}

data class TimeSyncResult(val bootCount: Long, val deviceUptimeMs: Long, val phoneUnixMs: Long)
