package dev.owl.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.owl.app.appContainer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * One sync pass as a WorkManager job (WorkManager = Android's scheduler for deferrable background
 * work; it survives process death and reboots).
 *
 * Two uses:
 * - [schedulePeriodic]: every 15 min (the shortest period WorkManager allows), a safety net for
 *   A2 when the app was swiped away and CDM presence did not fire. While [SyncService] runs,
 *   these passes simply queue behind the service's (SyncRunner serialises passes).
 * - [runOnce]: an expedited one-off pass when Android 12+ refuses to start the foreground service.
 *
 * minSdk 31: expedited work runs as a JobScheduler expedited job, so no [getForegroundInfo]
 * notification is needed (WorkManager only asks for it below API 31).
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.PERIODIC
        return try {
            val reached = applicationContext.appContainer.syncRunner.sync(trigger)
            Log.i(TAG, "worker sync ($trigger) reached pendant: $reached")
            // Not reaching the pendant is normal (out of range): no retry, the next period tries again.
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "worker sync ($trigger) failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "OwlSyncWorker"
        private const val KEY_TRIGGER = "trigger"
        private const val PERIODIC_NAME = "owl-sync-periodic"
        private const val ONCE_NAME = "owl-sync-once"

        /** Enqueue once a pendant is registered; KEEP makes repeated calls (every app start) harmless. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.PERIODIC.name))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Call when the pendant is unregistered. */
        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        fun runOnce(context: Context, trigger: SyncTrigger) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                .build()
            // KEEP: one queued/running pass is enough; another would only wait for it.
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
