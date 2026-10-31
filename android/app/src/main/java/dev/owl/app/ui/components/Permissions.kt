package dev.owl.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Permission checks for the onboarding checklist and the location switch. The UI never calls BLE itself. */
object OwlPermissions {
    val nearby = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val foregroundLocation = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    const val BACKGROUND_LOCATION = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasNearby(context: Context): Boolean = nearby.all { granted(context, it) }

    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun hasForegroundLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context): Boolean = granted(context, BACKGROUND_LOCATION)

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** System dialog asking to exempt Owl from Doze app standby (A2: sync must keep running). */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
}
