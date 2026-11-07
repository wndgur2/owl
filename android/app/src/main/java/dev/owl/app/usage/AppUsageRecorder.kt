package dev.owl.app.usage

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.owl.app.appContainer
import dev.owl.app.data.db.AppUsageDao
import dev.owl.app.data.db.AppUsageEntity
import dev.owl.app.model.AppUsageCard
import dev.owl.app.model.AppUsageRepository
import dev.owl.app.model.AppUsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The system side of A18; a fake in tests. */
interface UsageEventSource {
    /** 사용 정보 접근 is allowed. */
    fun hasAccess(): Boolean

    /** Events in [fromMs, toMs), or null if the system cannot answer yet (locked since boot). */
    fun events(fromMs: Long, toMs: Long): List<UsageEvent>?

    /** Names of the apps worth recording among [packages]; the rest (home screen, system UI, Owl) are left out. */
    fun labels(packages: Set<String>): Map<String, String>

    fun clock(): ClockReading
}

/** The switch and read position, kept in [dev.owl.app.data.SettingsStore]. */
interface AppUsageStateStore {
    val appUsageEnabled: Flow<Boolean>

    /** Off forgets the read position. On: the caller sets it with [setAppUsageCursor]. */
    suspend fun setAppUsageEnabled(enabled: Boolean)
    suspend fun appUsageCursor(): Long?
    suspend fun appUsageClock(): ClockReading?

    /** Read from [ms] next; [clock] is how the wall clock stood when this was written. */
    suspend fun setAppUsageCursor(ms: Long, clock: ClockReading)
}

class AndroidUsageSource(private val context: Context) : UsageEventSource {
    override fun hasAccess(): Boolean {
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    override fun events(fromMs: Long, toMs: Long): List<UsageEvent>? {
        val query = context.getSystemService(UsageStatsManager::class.java).queryEvents(fromMs, toMs) ?: return null
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (query.hasNextEvent()) {
            query.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Type.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEvent.Type.ALL_STOPPED
                else -> continue
            }
            out += UsageEvent(e.packageName, type, e.timeStamp)
        }
        return out
    }

    override fun labels(packages: Set<String>): Map<String, String> {
        val pm = context.packageManager
        // Negative priority: Settings' FallbackHome, only used until the first unlock.
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.priority >= 0 }.map { it.activityInfo.packageName }.toSet()
        return packages.filter { it != context.packageName && it !in home && pm.getLaunchIntentForPackage(it) != null }
            .mapNotNull { name ->
                // Uninstalled just now: nothing to name it with, and nobody to show it for.
                runCatching { name to pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrNull()
            }.toMap()
    }

    override fun clock() = ClockReading(
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
        wallMinusElapsedMs = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
    )
}

/**
 * A18: copies the system's usage events into our database. The system keeps them only for days, so
 * [sync] runs periodically and when the app opens. Rows go in before the cursor moves and duplicates
 * are ignored, so a crash in between or an overlapping read loses and repeats nothing (decisions #53).
 */
class AppUsageRecorder(
    private val dao: AppUsageDao,
    private val state: AppUsageStateStore,
    private val source: UsageEventSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppUsageRepository {
    private val lock = Mutex()

    override val enabled: Flow<Boolean> get() = state.appUsageEnabled
    override val hasRecords: Flow<Boolean> get() = dao.hasRecords()

    override fun hasAccess(): Boolean = source.hasAccess()

    override suspend fun setEnabled(on: Boolean) {
        if (!on) sync() // Keep what happened up to now; it is why the switch was on.
        lock.withLock {
            if (state.appUsageEnabled.first() == on) return
            if (on) state.setAppUsageCursor(clock(), source.clock())
            state.setAppUsageEnabled(on)
        }
    }

    override suspend fun sync() = withContext(Dispatchers.IO) { lock.withLock { read() } }

    private suspend fun read() {
        if (!state.appUsageEnabled.first() || !source.hasAccess()) return
        val now = clock()
        val reading = source.clock()
        val cursor = state.appUsageCursor()
        val from = cursor?.plus(UsageSessions.clockShiftMs(state.appUsageClock(), reading))
        if (from == null || from > now) {
            state.setAppUsageCursor(now, reading)
            return
        }
        val events = source.events(from, now) ?: return
        val window = UsageSessions.build(events, now)
        val labels = source.labels(window.finished.map { it.packageName }.toSet())
        dao.insertAll(
            window.finished.mapNotNull { s ->
                labels[s.packageName]?.let { AppUsageEntity(packageName = s.packageName, label = it, startMs = s.startMs, endMs = s.endMs) }
            },
        )
        state.setAppUsageCursor(window.nextCursorMs, reading)
    }

    override fun cards(sinceMs: Long?): Flow<List<AppUsageCard>> =
        dao.endingAfter(sinceMs ?: Long.MIN_VALUE).map(UsageSessions::cards)

    override fun summary(fromMs: Long, toMs: Long): Flow<List<AppUsageSummary>> =
        dao.overlapping(fromMs, toMs).map { UsageSessions.summarize(it, fromMs, toMs) }

    override suspend fun deleteAll() {
        lock.withLock {
            dao.deleteAll()
            // Sessions still waiting to be read would come back otherwise.
            if (state.appUsageEnabled.first()) state.setAppUsageCursor(clock(), source.clock())
        }
    }
}

class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appContainer.appUsage.sync()
        return Result.success()
    }

    companion object {
        private const val NAME = "app-usage"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageWorker>(6, TimeUnit.HOURS).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
