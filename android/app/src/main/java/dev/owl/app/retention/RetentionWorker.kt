package dev.owl.app.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.owl.app.appContainer
import dev.owl.app.data.db.SegmentDao
import dev.owl.app.model.RetentionPolicy
import java.io.File
import java.util.concurrent.TimeUnit

/** Deletes expired audio files and marks the rows (text stays). */
class RetentionManager(private val segments: SegmentDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun apply(policy: RetentionPolicy): Int {
        var deleted = 0
        for (s in segments.withAudio()) {
            if (!RetentionRules.shouldDelete(policy, s, clock())) continue
            s.filePath?.let { File(it).delete() }
            segments.markAudioDeleted(s.rowId)
            deleted++
        }
        return deleted
    }
}

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        c.retentionManager.apply(c.currentRetention())
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "retention",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
