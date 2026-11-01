package dev.owl.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import dev.owl.app.audio.OpusPlayer
import dev.owl.app.ble.BleTransport
import dev.owl.app.ble.CdmRegistrar
import dev.owl.app.location.LocationRecorder
import dev.owl.app.sync.SyncService
import dev.owl.app.sync.SyncWorker
import dev.owl.app.data.LocationStore
import dev.owl.app.data.PlaceResolver
import dev.owl.app.data.EntryIndexer
import dev.owl.app.data.PlacesRepositoryImpl
import dev.owl.app.data.RegisteredDevice
import dev.owl.app.data.RegistrationStore
import dev.owl.app.data.RoomSyncDb
import dev.owl.app.data.SettingsStore
import dev.owl.app.data.TimelineRepositoryImpl
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.device.OwlTransport
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
import dev.owl.app.model.TimelineRepository
import dev.owl.app.model.PlacesRepository
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
import dev.owl.app.ui.theme.nightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    private val entryIndexer by lazy { EntryIndexer(database) }
    val retentionManager by lazy { RetentionManager(database.segments()) }
    val transcriptionQueue by lazy { TranscriptionQueue(database) { transcriber() } }

    suspend fun currentRetention(): RetentionPolicy = settingsStore.retention.first()

    /**
     * STT engine: demo sentences only in debug builds — for the fake pendant, or for any pendant
     * after the debug `demo-stt` command (QA over the real BLE path, docs/qa/). See stt/Transcriber.kt.
     */
    private suspend fun transcriber(): Transcriber {
        val fake = settingsStore.current()?.isFake == true
        val demo = BuildConfig.DEBUG && (fake || debugPrefs.getBoolean(DEBUG_DEMO_STT, false))
        return if (demo) DemoTranscriber() else PlaceholderTranscriber()
    }

    /** Debug-build switches set over adb (FakeDeviceCommandReceiver). Never read in release. */
    val debugPrefs: android.content.SharedPreferences by lazy { context.getSharedPreferences("debug", Context.MODE_PRIVATE) }

    /** Debug: re-resolves places after fixes were imported (FakeDeviceCommandReceiver). */
    suspend fun resolvePlaces() {
        placeResolver.resolve()
    }

    val timelineRepository: TimelineRepository by lazy {
        TimelineRepositoryImpl(database, settingsStore.retention, reindex = { entryIndexer.rebuild() })
    }

    private val coordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            scope = appScope,
            settings = settingsStore,
            statusDao = database.status(),
            syncDb = syncDb,
            engine = SyncEngine(segmentFiles, syncDb, log = { android.util.Log.i("OwlSync", it) }),
            launchManualSync = { SyncService.syncNow(context) },
            transportFor = ::transportFor,
            afterSync = { report ->
                settingsStore.current()?.let { syncDb.placeUnknownTimes(it.address) }
                placeResolver.resolve()
                entryIndexer.rebuild()
                if (report.fetched > 0) TranscribeWorker.enqueue(context)
                retentionManager.apply(currentRetention())
            },
        )
    }
    val deviceController: DeviceController get() = coordinator
    val syncRunner: SyncRunner get() = coordinator

    /**
     * CDM registration (A1), plus what has to happen around it: start syncing right away and
     * keep the 15-minute WorkManager safety net only while a pendant is registered.
     */
    val deviceRegistrar: DeviceRegistrar by lazy {
        val cdm = CdmRegistrar(context, registrationStore, fakeAvailable = BuildConfig.DEBUG)
        object : DeviceRegistrar by cdm {
            override suspend fun onAssociationResult(data: Intent?) = cdm.onAssociationResult(data).onSuccess { onRegistered() }
            override suspend fun registerFakeDevice() = cdm.registerFakeDevice().onSuccess { onRegistered() }
            override suspend fun unregister() {
                coordinator.forgetOnDevice() // UNPAIR on the pendant first, while we still have the bond
                cdm.unregister()
                SyncWorker.cancelPeriodic(context)
                coordinator.setDevicePresent(false)
            }
        }
    }

    private suspend fun onRegistered() {
        SyncWorker.schedulePeriodic(context)
        if (settingsStore.current()?.isFake == true) coordinator.setDevicePresent(true)
        SyncService.start(context, SyncTrigger.DEVICE_APPEARED)
    }

    val locationRecorder by lazy { LocationRecorder(context) }
    val placesRepository: PlacesRepository by lazy { PlacesRepositoryImpl(database, placeResolver) }
    val exporter: Exporter by lazy { JsonExporter(database, context.contentResolver) }

    /** A new player per Entry screen; the caller releases it. */
    fun newAudioPlayer(): AudioPlayer = OpusPlayer()

    private suspend fun transportFor(dev: RegisteredDevice): OwlTransport =
        if (dev.isFake) fakeDevice() else BleTransport(context, dev.address)

    // --- fake pendant (debug builds) -----------------------------------------

    private val fakeLock = Mutex()
    private var fake: FakeOwlDevice? = null
    private val fakeDemo by lazy { FakeDemo(database, locationSink, appScope) }

    private suspend fun fakeDevice(): FakeOwlDevice = fakeLock.withLock {
        fake ?: run {
            check(BuildConfig.DEBUG) { "fake device is debug-only" }
            val lastId = database.segments().maxSegmentId("fake")
            val lastBoot = database.segments().maxBootCount("fake") ?: 2
            fakeDemo.createDevice(firstRun = lastId == null, nextId = (lastId ?: 0) + 1, lastBoot = lastBoot).also {
                fake = it
                fakeDemo.startRecordingLoop(it)
            }
        }
    }

    /** Debug: adb commands for the fake pendant (FakeDeviceCommandReceiver). */
    suspend fun fakeCommand(cmd: String, arg: Long?): String = fakeDemo.command(fakeDevice(), cmd, arg)

    private val openedThisProcess = AtomicBoolean(false)

    /**
     * MainActivity was created. The first time in this process is an app start; later ones are
     * the same visit (rotation, theme change recreating the screen).
     */
    fun onScreenCreated() {
        if (openedThisProcess.compareAndSet(false, true)) onAppOpened()
    }

    /** Called when the user opens the app (A2a: reconnect on every app start). */
    fun onAppOpened() {
        RetentionWorker.schedule(context)
        TranscribeWorker.enqueue(context)
        appScope.launch {
            // Data from before an app update (or a v1 DB) gets grouped and labelled right away.
            runCatching { placeResolver.resolve(); entryIndexer.rebuild() }
            val dev = settingsStore.current() ?: return@launch
            if (dev.isFake) coordinator.setDevicePresent(true)
            SyncService.start(context, SyncTrigger.APP_START)
        }
    }

    /** A5: follow the 장소 기록 setting (LocationRecorder remembers it for BootReceiver). */
    private fun followLocationSetting() {
        appScope.launch {
            settingsStore.locationEnabled.collect { on ->
                if (on && locationRecorder.hasForegroundPermission()) locationRecorder.start()
                else if (!on) locationRecorder.stop()
            }
        }
    }

    /**
     * 설정 › 테마 → the app's night mode. Android keeps it, so the window shown before Compose's first
     * frame (res/values-night) is already in the chosen theme on the next cold start.
     */
    private fun followThemeSetting() {
        appScope.launch {
            val uiModes = context.getSystemService(UiModeManager::class.java)
            settingsStore.themeMode.collect { uiModes.setApplicationNightMode(it.nightMode) }
        }
    }

    init {
        followLocationSetting()
        followThemeSetting()
    }
}

/** Debug pref key: demo sentences for a real (BLE) pendant too. */
const val DEBUG_DEMO_STT = "demo_stt"
