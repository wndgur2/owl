package dev.owl.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Location update registrations do not survive a reboot, so re-register them at boot.
 * Only when recording was on before ([LocationRecorder.isActive] mirrors the 장소 기록 setting)
 * and the permission is still granted ([LocationRecorder.start] checks).
 *
 * Sync needs nothing here: CDM presence observing and WorkManager periodic work both survive reboots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        LocationRecorder(context).restoreAfterBoot()
    }
}
