package dev.owl.app.sync

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.owl.app.MainActivity
import dev.owl.app.R
import dev.owl.app.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A2 / A2a: the foreground sync loop while the pendant is in range.
 *
 * Why a *foreground* service: Android kills or freezes background work within minutes. A
 * foreground service (with its ongoing notification) may keep running; type `connectedDevice`
 * is the one meant for "talking to a companion device" and has no daily time limit.
 *
 * Loop: sync now → wait [SYNC_INTERVAL_MS] (or less, on "지금 동기화") → sync again, for as long as
 * [SyncRunner.devicePresent] is true. When CDM says the pendant left, the loop ends and the
 * service stops. A start while the loop is running never creates a second loop.
 *
 * Waiting while the screen is off: coroutine `delay` uses a clock that stops while the CPU is in
 * deep sleep (Doze), so 15 minutes could stretch to hours. An `AlarmManager` alarm
 * (`setAndAllowWhileIdle`, no special permission) wakes the CPU and nudges the loop. It is inexact,
 * so in deep Doze a pass may come a few minutes late; N5 (30 min) still holds.
 */
class SyncService : LifecycleService() {

    // Touched only on the main thread (onStartCommand and lifecycleScope both run there).
    private var loop: Job? = null
    private var syncing = true

    /** Early wake-ups for the waiting loop ("지금 동기화", alarm tick). */
    private val wake = Channel<SyncTrigger>(Channel.CONFLATED)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.PERIODIC
        val running = loop?.isActive == true

        if (intent?.action == ACTION_TICK) {
            // Alarm nudge (plain startService, no startForeground needed). Stale if the loop ended.
            if (running) wake.trySend(trigger) else stopSelf()
            return START_NOT_STICKY
        }

        // Every startForegroundService() must be answered with startForeground() within a few
        // seconds, even when we are already in the foreground, so do it on every start.
        if (!goForeground()) {
            SyncWorker.runOnce(this, trigger)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }

        if (running) {
            if (intent?.action == ACTION_SYNC_NOW) wake.trySend(trigger)
        } else {
            loop = lifecycleScope.launch { runLoop(trigger) }
        }
        // Not sticky: if the system kills us, CDM / WorkManager restart sync; a blind restart
        // without knowing presence would only spin.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelAlarm()
        super.onDestroy()
    }

    private suspend fun runLoop(first: SyncTrigger) {
        val runner = appContainer.syncRunner
        var trigger = first
        try {
            while (true) {
                runPass(runner, trigger)
                // Triggers that came in during the pass were served by it (SyncRunner serialises).
                wake.tryReceive()
                if (!runner.devicePresent.value) break
                updateNotification(syncing = false)
                trigger = awaitNextTrigger(runner) ?: break
            }
        } finally {
            cancelAlarm()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runPass(runner: SyncRunner, trigger: SyncTrigger) {
        updateNotification(syncing = true)
        // Keep the CPU awake for the transfer; BLE keeps running anyway, but our coroutines must
        // too. Bounded so a stuck pass cannot drain the battery.
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "owl:sync")
        wakeLock.acquire(PASS_WAKE_LOCK_MS)
        try {
            val reached = runner.sync(trigger)
            Log.i(TAG, "sync ($trigger) reached pendant: $reached")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync ($trigger) failed", e)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** Next trigger, or null when the pendant is gone. */
    private suspend fun awaitNextTrigger(runner: SyncRunner): SyncTrigger? {
        val deadline = SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS
        scheduleAlarm(deadline)
        return coroutineScope {
            val gone = async { runner.devicePresent.first { !it } }
            try {
                waitUntil(deadline, gone)
            } finally {
                gone.cancel()
            }
        }
    }

    private sealed interface Wake {
        class Trigger(val trigger: SyncTrigger) : Wake
        data object Gone : Wake
    }

    private suspend fun waitUntil(deadline: Long, gone: Deferred<Boolean>): SyncTrigger? {
        while (true) {
            // elapsedRealtime counts deep sleep, unlike the coroutine timer, so it decides.
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return SyncTrigger.PERIODIC
            val woke = withTimeoutOrNull(remaining) {
                select<Wake> {
                    wake.onReceive { Wake.Trigger(it) }
                    gone.onAwait { Wake.Gone }
                }
            }
            when (woke) {
                null -> Unit // timer fired; re-check the deadline
                Wake.Gone -> return null
                // An alarm tick (PERIODIC) is only a nudge; "지금 동기화" syncs right away.
                is Wake.Trigger -> if (woke.trigger != SyncTrigger.PERIODIC) return woke.trigger
            }
        }
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(syncing),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        // Android 12+: not allowed to go foreground from the background right now.
        Log.w(TAG, "foreground start not allowed; falling back to WorkManager", e)
        false
    } catch (e: SecurityException) {
        // Android 14+: connectedDevice needs BLUETOOTH_CONNECT granted at runtime.
        Log.w(TAG, "foreground start refused; falling back to WorkManager", e)
        false
    }

    private fun updateNotification(syncing: Boolean) {
        this.syncing = syncing
        // Without POST_NOTIFICATIONS (Android 13+) the notification is hidden but the service
        // still runs; skip the update instead of throwing.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(syncing))
    }

    private fun notification(syncing: Boolean): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // TODO: replace with a monochrome Owl status icon once one exists in res/drawable.
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(getString(if (syncing) R.string.sync_notification_syncing else R.string.sync_notification_idle))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getService(
        this, 0,
        Intent(this, SyncService::class.java).setAction(ACTION_TICK).putExtra(EXTRA_TRIGGER, SyncTrigger.PERIODIC.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAlarm(atElapsedMs: Long) {
        getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, alarmIntent())
    }

    private fun cancelAlarm() {
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())
    }

    companion object {
        private const val TAG = "OwlSyncService"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        private const val PASS_WAKE_LOCK_MS = 10 * 60 * 1000L

        private const val ACTION_START = "dev.owl.app.sync.START"
        private const val ACTION_SYNC_NOW = "dev.owl.app.sync.SYNC_NOW"
        private const val ACTION_TICK = "dev.owl.app.sync.TICK"
        private const val EXTRA_TRIGGER = "trigger"

        /** Starts the loop (no-op if it already runs). Used by CDM presence and app start. */
        fun start(context: Context, trigger: SyncTrigger) = startWith(context, ACTION_START, trigger)

        /** "지금 동기화" (A2a): starts the loop, or wakes a waiting one for an immediate pass. */
        fun syncNow(context: Context) = startWith(context, ACTION_SYNC_NOW, SyncTrigger.MANUAL)

        private fun startWith(context: Context, action: String, trigger: SyncTrigger) {
            val intent = Intent(context, SyncService::class.java)
                .setAction(action)
                .putExtra(EXTRA_TRIGGER, trigger.name)
            try {
                context.startForegroundService(intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Android 12+ background-start restriction (e.g. CDM callback without the
                // companion exemption): still sync once, as an expedited job.
                Log.w(TAG, "startForegroundService not allowed; using WorkManager", e)
                SyncWorker.runOnce(context, trigger)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startForegroundService failed; using WorkManager", e)
                SyncWorker.runOnce(context, trigger)
            }
        }

        internal fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.sync_channel_description) },
            )
        }
    }
}
