package dev.owl.app

import android.content.Context
import dev.owl.app.data.LocationStore
import dev.owl.app.data.PlaceResolver
import dev.owl.app.data.RegisteredDevice
import dev.owl.app.data.RegistrationStore
import dev.owl.app.data.RoomSyncDb
import dev.owl.app.data.SettingsStore
import dev.owl.app.data.TimelineRepositoryImpl
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.device.OwlTransport
import dev.owl.app.device.TransportException
import dev.owl.app.device.fake.FakeDemo
import dev.owl.app.device.fake.FakeOwlDevice
import dev.owl.app.export.JsonExporter
import dev.owl.app.location.LocationSink
import dev.owl.app.model.AudioPlayer
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.Exporter
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.StubAudioPlayer
import dev.owl.app.model.StubDeviceRegistrar
import dev.owl.app.model.TimelineRepository
import dev.owl.app.retention.RetentionManager
import dev.owl.app.retention.RetentionWorker
import dev.owl.app.stt.DemoTranscriber
import dev.owl.app.stt.PlaceholderTranscriber
import dev.owl.app.stt.TranscribeWorker
import dev.owl.app.stt.Transcriber
import dev.owl.app.stt.TranscriptionQueue
import dev.owl.app.sync.SegmentFiles
import dev.owl.app.sync.SyncCoordinator
import dev.owl.app.sync.SyncEngine
import dev.owl.app.sync.SyncRunner
import dev.owl.app.sync.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manual dependency injection: one instance per process, created in [OwlApp].
 * Anything that needs a dependency gets it from here (`context.appContainer`).
 * No DI framework on purpose — every wire is visible in this one file.
 */
class AppContainer(private val context: Context) {
    /** Lives as long as the process; for work that must outlive a screen (sync, seeding). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: OwlDatabase by lazy { OwlDatabase.create(context) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val settingsRepository: SettingsRepository get() = settingsStore
    val registrationStore: RegistrationStore get() = settingsStore

    private val segmentFiles by lazy { SegmentFiles(File(context.filesDir, "segments")) }
    private val syncDb by lazy { RoomSyncDb(database) }

    val locationSink: LocationSink by lazy { LocationStore(database) }
    private val placeResolver by lazy { PlaceResolver(database) }
    val retentionManager by lazy { RetentionManager(database.segments()) }
    val transcriptionQueue by lazy { TranscriptionQueue(database) { transcriber() } }

    suspend fun currentRetention(): RetentionPolicy = settingsStore.retention.first()

    /** STT engine: demo sentences only for the fake pendant in debug builds (see stt/Transcriber.kt). */
    private suspend fun transcriber(): Transcriber {
        val fake = settingsStore.current()?.isFake == true
        return if (fake && BuildConfig.DEBUG) DemoTranscriber() else PlaceholderTranscriber()
    }

    val timelineRepository: TimelineRepository by lazy { TimelineRepositoryImpl(database, settingsStore.retention) }

    private val coordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            scope = appScope,
            settings = settingsStore,
            statusDao = database.status(),
            syncDb = syncDb,
            engine = SyncEngine(segmentFiles, syncDb),
            transportFor = ::transportFor,
            afterSync = { report ->
                placeResolver.resolve()
                if (report.fetched > 0) TranscribeWorker.enqueue(context)
                retentionManager.apply(currentRetention())
            },
        )
    }
    val deviceController: DeviceController get() = coordinator
    val syncRunner: SyncRunner get() = coordinator

    val deviceRegistrar: DeviceRegistrar by lazy { StubDeviceRegistrar() }
    val exporter: Exporter by lazy { JsonExporter(database, context.contentResolver) }

    /** A new player per Entry screen; the caller releases it. */
    fun newAudioPlayer(): AudioPlayer = StubAudioPlayer()

    private suspend fun transportFor(dev: RegisteredDevice): OwlTransport =
        if (dev.isFake) fakeDevice() else throw TransportException("BLE transport not wired yet")

    // --- fake pendant (debug builds) -----------------------------------------

    private val fakeLock = Mutex()
    private var fake: FakeOwlDevice? = null
    private val fakeDemo by lazy { FakeDemo(database, locationSink, appScope) }

    private suspend fun fakeDevice(): FakeOwlDevice = fakeLock.withLock {
        fake ?: run {
            check(BuildConfig.DEBUG) { "fake device is debug-only" }
            val lastId = database.segments().maxSegmentId("fake")
            fakeDemo.createDevice(firstRun = lastId == null, nextId = (lastId ?: 0) + 1).also {
                fake = it
                fakeDemo.startRecordingLoop(it)
            }
        }
    }

    /** Debug: adb commands for the fake pendant (FakeDeviceCommandReceiver). */
    suspend fun fakeCommand(cmd: String, arg: Long?): String = fakeDemo.command(fakeDevice(), cmd, arg)

    /** Called when the user opens the app (A2a: reconnect on every app start). */
    fun onAppOpened() {
        RetentionWorker.schedule(context)
        TranscribeWorker.enqueue(context)
        appScope.launch {
            val dev = settingsStore.current() ?: return@launch
            if (dev.isFake) coordinator.setDevicePresent(true)
            coordinator.sync(SyncTrigger.APP_START)
        }
    }
}
