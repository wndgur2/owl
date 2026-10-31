package dev.owl.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationResult
import dev.owl.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives the location fixes that [LocationRecorder]'s PendingIntent delivers, possibly in a
 * process that Android just started for this broadcast.
 *
 * `goAsync()`: a receiver normally has to finish inside onReceive (main thread). goAsync keeps
 * the broadcast (and so the process) alive until we call `finish()`, giving the database write a
 * few seconds on a background thread. Android allows about 10 s.
 */
class LocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Null for "location availability changed" broadcasts, which carry no fix.
        val result = LocationResult.extractResult(intent) ?: return
        val sink = context.appContainer.locationSink
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(WRITE_TIMEOUT_MS) {
                    for (location in result.locations) {
                        sink.record(
                            timeMs = location.time,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not store location", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "OwlLocation"
        const val ACTION_LOCATION = "dev.owl.app.location.UPDATE"
        private const val WRITE_TIMEOUT_MS = 8_000L
    }
}
