package dev.owl.app.stt

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.owl.app.appContainer
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.data.db.SentenceEntity
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SegmentState
import java.io.File

/** Runs STT over every RECEIVED recording, one at a time (A6, A8a). */
class TranscriptionQueue(private val db: OwlDatabase, private val transcriber: suspend () -> Transcriber) {
    suspend fun runAll(): Int {
        val engine = transcriber()
        if (!engine.available) return 0
        // TRANSCRIBING rows left over from a killed run are retried.
        val todo = db.segments().withState(SegmentState.TRANSCRIBING) + db.segments().withState(SegmentState.RECEIVED)
        var done = 0
        for (s in todo) {
            val file = s.filePath?.let(::File)?.takeIf { it.exists() } ?: continue
            db.segments().setState(s.rowId, SegmentState.TRANSCRIBING)
            try {
                val sentences = engine.transcribe(file, s.durationMs ?: 0)
                db.sentences().replace(
                    s.rowId,
                    sentences.mapIndexed { i, t -> SentenceEntity(0, s.rowId, i, t.startMs, t.endMs, t.text) },
                )
                db.segments().setState(s.rowId, SegmentState.DONE)
                done++
            } catch (e: Exception) {
                Log.w("OwlStt", "transcription of ${s.segmentId} failed", e)
                db.segments().setState(s.rowId, SegmentState.RECEIVED)
            }
        }
        return done
    }
}

class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        if (c.transcriptionQueue.runAll() > 0 && c.currentRetention() == RetentionPolicy.AFTER_TRANSCRIPTION) {
            c.retentionManager.apply(RetentionPolicy.AFTER_TRANSCRIPTION)
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcribe",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TranscribeWorker>().build(),
            )
        }
    }
}
