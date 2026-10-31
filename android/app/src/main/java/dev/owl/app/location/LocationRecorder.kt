package dev.owl.app.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeUnit

/**
 * A5: records the phone's position every few minutes (the pendant has no GPS, decisions.md #4).
 *
 * Updates are delivered to a **PendingIntent** that targets [LocationReceiver], not to a callback
 * in our process. A PendingIntent is a token the system holds on our behalf: Google Play services
 * fires it even when our process is dead, and Android starts the process just for the broadcast.
 * That is what makes "앱 프로세스가 없어도 기록된다" work. Callback-style updates would stop the
 * moment the app is swiped away.
 *
 * Balanced power accuracy (~100 m, mostly Wi-Fi/cell) every 5 min or 50 m is enough to tell
 * "home / office / café" apart, at a small battery cost.
 *
 * No reverse geocoding (coordinates → "서울 강남구 ..."): Android's `Geocoder` sends coordinates to
 * a network service, which would violate PRD N8 (nothing leaves the phone). Fixes are stored as
 * plain coordinates.
 *
 * Location registrations are lost on reboot, so [start] remembers that recording is on and
 * [BootReceiver] calls [restoreAfterBoot]. The 장소 기록 setting must call [start] / [stop]; the
 * remembered flag then always mirrors that setting.
 */
class LocationRecorder(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Fine or coarse ("앱 사용 중") *and* background ("항상 허용"): needed for A5 with the app closed. */
    fun hasPermission(): Boolean = hasForegroundPermission() && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Enough to request updates; without background permission they only arrive while the app is visible. */
    fun hasForegroundPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** True while recording is switched on (survives restarts; used after reboot). */
    val isActive: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)

    /**
     * Starts (or re-registers; calling twice is harmless) periodic updates. Returns false when
     * location permission is missing.
     */
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no location permission; not recording")
            return false
        }
        if (!hasPermission()) Log.w(TAG, "no background location permission; fixes only while the app is visible")
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .build()
        try {
            client.requestLocationUpdates(request, pendingIntent())
                .addOnFailureListener { Log.w(TAG, "requestLocationUpdates failed", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "permission revoked", e)
            return false
        }
        prefs.edit { putBoolean(KEY_ACTIVE, true) }
        return true
    }

    fun stop() {
        prefs.edit { putBoolean(KEY_ACTIVE, false) }
        client.removeLocationUpdates(pendingIntent())
            .addOnFailureListener { Log.w(TAG, "removeLocationUpdates failed", it) }
    }

    /** After reboot: re-register only if recording was on before (i.e. the user's setting is on). */
    fun restoreAfterBoot() {
        if (isActive) start()
    }

    /**
     * FLAG_MUTABLE is required: Play services adds the LocationResult to this intent when it fires
     * it, and an immutable PendingIntent cannot carry those extras (Android 12+ makes us choose).
     * The intent is explicit (our own receiver), so mutability does not let other apps redirect it.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, LocationReceiver::class.java).setAction(LocationReceiver.ACTION_LOCATION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "OwlLocation"
        const val PREFS = "location_recorder"
        const val KEY_ACTIVE = "active"
        const val REQUEST_CODE = 1
        val INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)
        const val MIN_DISTANCE_M = 50f
    }
}
