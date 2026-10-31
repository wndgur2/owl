package dev.owl.app.ble

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.owl.app.appContainer
import dev.owl.app.sync.SyncService
import dev.owl.app.sync.SyncTrigger

/**
 * Woken by CompanionDeviceManager when the registered pendant comes into or leaves BLE range
 * (presence observing is turned on in [CdmRegistrar]). The system binds this service even when
 * our process is dead, which is what makes A2 work after the app was swiped away.
 *
 * Appeared → mark present and start the foreground [SyncService] loop.
 * Disappeared → mark absent; the loop notices and stops itself.
 *
 * Android 36 may deliver both the new [onDevicePresenceEvent] and the older callbacks; both paths
 * are idempotent (SyncService never runs two loops), so double delivery is harmless.
 */
class OwlCompanionService : CompanionDeviceService() {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Superseded by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Superseded by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared()

    // API 31–32 only; on 33+ the system calls the AssociationInfo overloads above.
    @Deprecated("API < 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) appeared()
    }

    @Deprecated("API < 33")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) disappeared()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> appeared()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> disappeared()
            else -> Unit
        }
    }

    private fun appeared() {
        Log.i(TAG, "pendant appeared")
        appContainer.syncRunner.setDevicePresent(true)
        SyncService.start(this, SyncTrigger.DEVICE_APPEARED)
    }

    private fun disappeared() {
        Log.i(TAG, "pendant disappeared")
        appContainer.syncRunner.setDevicePresent(false)
    }

    private companion object {
        const val TAG = "OwlCompanion"
    }
}
