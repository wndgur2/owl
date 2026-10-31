package dev.owl.app.sync

import android.util.Log
import dev.owl.app.data.RegisteredDevice
import dev.owl.app.data.SettingsStore
import dev.owl.app.data.db.StatusDao
import dev.owl.app.device.Battery
import dev.owl.app.device.DeviceErrorException
import dev.owl.app.device.OwlClient
import dev.owl.app.device.OwlTransport
import dev.owl.app.device.TransportException
import dev.owl.app.model.Connection
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceUiState
import dev.owl.app.model.SyncProgress
import dev.owl.app.protocol.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns "talking to the pendant": runs sync passes with retries, decides "연결 끊김"
 * (decisions.md #16), sends SET_RECORDING, and exposes [DeviceUiState] for the Device screen.
 *
 * One pass at a time: a second [sync] call while one runs just waits for the same result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val statusDao: StatusDao,
    private val syncDb: SyncDb,
    private val engine: SyncEngine,
    /** Real BLE or the fake device for the registered pendant. */
    private val transportFor: suspend (RegisteredDevice) -> OwlTransport,
    /** Runs after every successful pass: STT queue, place resolution, retention. */
    private val afterSync: suspend (SyncReport) -> Unit = {},
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000),
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncRunner, DeviceController {

    /** How the UI's "지금 동기화" is executed; AppContainer routes it through SyncService. */
    var launchManualSync: (SyncTrigger) -> Unit = { trigger -> scope.launch { sync(trigger) } }

    private data class Live(
        val connection: Connection = Connection.UNKNOWN,
        val syncing: Boolean = false,
        val progress: SyncProgress? = null,
        val changingRecording: Boolean = false,
        /** Set right after SET_RECORDING succeeds, until the next STATUS sample confirms it. */
        val recordingOverride: Boolean? = null,
    )

    private val live = MutableStateFlow(Live())
    private val _present = MutableStateFlow(false)
    override val devicePresent: StateFlow<Boolean> = _present.asStateFlow()

    private val startLock = Mutex()
    private val linkLock = Mutex()
    private var running: Deferred<Boolean>? = null

    @Volatile
    private var activeClient: OwlClient? = null

    override fun setDevicePresent(present: Boolean) {
        _present.value = present
    }

    // --- DeviceController ----------------------------------------------------

    private val latestStatus = settings.device.flatMapLatest { dev ->
        if (dev == null) flowOf(null) else statusDao.latest(dev.address)
    }

    override val state: StateFlow<DeviceUiState> = combine(
        settings.device, settings.syncMeta, latestStatus, live,
    ) { dev, meta, status, l -> Quad(dev, meta, status, l) }
        .mapLatest { (dev, meta, status, l) ->
            val history = if (dev != null && status != null) {
                statusDao.since(dev.address, clock() - 24 * 3_600_000L).map { it.timeMs to it.batteryMv }
            } else {
                emptyList()
            }
            val connection = when {
                l.connection != Connection.UNKNOWN -> l.connection
                meta.disconnected -> Connection.DISCONNECTED
                else -> Connection.UNKNOWN
            }
            DeviceUiState(
                registered = dev != null,
                name = dev?.name,
                isFake = dev?.isFake == true,
                connection = connection,
                syncing = l.syncing,
                progress = l.progress,
                recordingOn = l.recordingOverride ?: status?.let { it.flags and Status.FLAG_RECORDING != 0 },
                storageLow = status?.let { it.flags and Status.FLAG_STORAGE_LOW != 0 } ?: false,
                storageFull = status?.let { it.flags and Status.FLAG_STORAGE_FULL != 0 } ?: false,
                batteryPercent = status?.let { Battery.percent(it.batteryMv) },
                batteryHoursLeft = status?.let { Battery.hoursLeft(it.batteryMv, history) },
                storageUsedMb = status?.let { it.storageUsedKb / 1024.0 },
                storageTotalMb = status?.let { it.storageTotalKb / 1024.0 },
                pendingSegments = status?.pendingSegments,
                lastSyncAtMs = meta.lastSyncAtMs,
                lastSeenAtMs = meta.lastSeenAtMs,
                firmwareVersion = status?.firmwareVersion,
                changingRecording = l.changingRecording,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, DeviceUiState())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    override fun syncNow() = launchManualSync(SyncTrigger.MANUAL)

    override fun reconnect() = launchManualSync(SyncTrigger.RECONNECT)

    override suspend fun setRecording(on: Boolean): Result<Unit> {
        live.value = live.value.copy(changingRecording = true)
        try {
            // Reuse the open link if a sync is running (requests are serialised inside OwlClient).
            activeClient?.takeIf { it.connected.value }?.let { client ->
                client.setRecording(on)
                live.value = live.value.copy(recordingOverride = on)
                return Result.success(Unit)
            }
            val dev = settings.current() ?: return Result.failure(IllegalStateException("등록된 목걸이가 없어요"))
            return linkLock.withLock {
                withClient(dev) { client ->
                    client.setRecording(on)
                    syncDb.recordStatus(dev.address, client.readStatus(), clock())
                    settings.markSeen(clock())
                }
                live.value = live.value.copy(recordingOverride = null, connection = Connection.CONNECTED)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SET_RECORDING failed", e)
            return Result.failure(Exception("목걸이에 연결하지 못했어요", e))
        } finally {
            live.value = live.value.copy(changingRecording = false)
        }
    }

    // --- SyncRunner ------------------------------------------------------------

    override suspend fun sync(trigger: SyncTrigger): Boolean {
        val job = startLock.withLock {
            running?.takeIf { it.isActive } ?: scope.async { linkLock.withLock { runWithRetries(trigger) } }.also { running = it }
        }
        return job.await()
    }

    private suspend fun runWithRetries(trigger: SyncTrigger): Boolean {
        val dev = settings.current() ?: return false
        live.value = live.value.copy(syncing = true, connection = Connection.CONNECTING, progress = null)
        try {
            for (attempt in 0..retryDelaysMs.size) {
                try {
                    val report = withClient(dev) { client ->
                        settings.markSeen(clock())
                        live.value = live.value.copy(connection = Connection.CONNECTED)
                        engine.run(client, dev.address) { done, total, fraction ->
                            live.value = live.value.copy(progress = SyncProgress(done, total, fraction))
                        }
                    }
                    settings.markSynced(clock())
                    live.value = live.value.copy(connection = Connection.CONNECTED, recordingOverride = null)
                    Log.i(TAG, "sync ($trigger) ok: $report")
                    runCatching { afterSync(report) }.onFailure { Log.w(TAG, "afterSync failed", it) }
                    return true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "sync ($trigger) attempt ${attempt + 1} failed: ${e.message}")
                    if (e !is SyncFailedException && e !is TransportException && e !is DeviceErrorException) {
                        Log.e(TAG, "unexpected sync error", e)
                    }
                    live.value = live.value.copy(connection = Connection.CONNECTING, progress = null)
                    retryDelaysMs.getOrNull(attempt)?.let { delay(it) }
                }
            }
            settings.markDisconnected()
            live.value = live.value.copy(connection = Connection.DISCONNECTED)
            return false
        } finally {
            live.value = live.value.copy(syncing = false, progress = null)
        }
    }

    private suspend fun <T> withClient(dev: RegisteredDevice, block: suspend (OwlClient) -> T): T {
        val client = OwlClient(transportFor(dev), scope, clock = clock)
        client.connect()
        activeClient = client
        try {
            return block(client)
        } finally {
            activeClient = null
            runCatching { client.disconnect() }
        }
    }

    private companion object {
        const val TAG = "OwlSync"
    }
}
