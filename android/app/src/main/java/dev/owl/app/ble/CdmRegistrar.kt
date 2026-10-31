package dev.owl.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import dev.owl.app.R
import dev.owl.app.data.RegisteredDevice
import dev.owl.app.data.RegistrationStore
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.protocol.OwlProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * A1: registers the pendant once through **CompanionDeviceManager** (CDM).
 *
 * CDM is Android's "pair a wearable" system: the system scans, shows its own chooser dialog,
 * and remembers the association. In return the app gets (a) no location permission needed for
 * the scan, and (b) presence callbacks: [OwlCompanionService] is woken whenever the registered
 * pendant comes into or goes out of BLE range, even when our process is dead (A2).
 */
class CdmRegistrar(
    context: Context,
    private val store: RegistrationStore,
    fakeAvailable: Boolean,
) : DeviceRegistrar {

    private val appContext = context.applicationContext
    private val cdm: CompanionDeviceManager? = appContext.getSystemService(CompanionDeviceManager::class.java)

    override val registeredName: Flow<String?> = store.device.map { it?.name }

    override val fakeDeviceAvailable: Boolean = fakeAvailable

    override suspend fun startAssociation(): Result<IntentSender?> {
        val cdm = cdm ?: return failure(R.string.cdm_unsupported)

        // Only show devices that advertise as "Owl-XXXX" *and* carry the Owl service UUID
        // (protocol §2), so random BLE gadgets nearby never appear in the chooser.
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(Pattern.quote(OwlProtocol.NAME_PREFIX) + ".*"))
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(OwlProtocol.SERVICE_UUID)).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            // One match → a simple "Owl-3F2A에 연결할까요?" confirmation instead of a list.
            .setSingleDevice(true)
            .build()

        val sender = withTimeoutOrNull(ASSOCIATE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<IntentSender?>> { cont ->
                val callback = object : CompanionDeviceManager.Callback() {
                    // API 33+: chooser ready.
                    override fun onAssociationPending(intentSender: IntentSender) {
                        if (cont.isActive) cont.resume(Result.success(intentSender))
                    }

                    // API 31–32 name for the same event.
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onDeviceFound(intentSender: IntentSender) {
                        if (cont.isActive) cont.resume(Result.success(intentSender))
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.w(TAG, "association failed: $error")
                        if (cont.isActive) cont.resume(failure(R.string.cdm_not_found))
                    }
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        cdm.associate(request, appContext.mainExecutor, callback)
                    } else {
                        @Suppress("DEPRECATION")
                        cdm.associate(request, callback, Handler(Looper.getMainLooper()))
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "associate threw", e)
                    if (cont.isActive) cont.resume(failure(R.string.cdm_not_found))
                }
            }
        }
        return sender ?: failure(R.string.cdm_not_found)
    }

    override suspend fun onAssociationResult(data: Intent?): Result<String> {
        if (data == null) return failure(R.string.cdm_cancelled)

        // EXTRA_DEVICE holds the ScanResult for BLE filters (still sent on 33+, deprecated there).
        // Its scan record has the advertised name without needing BLUETOOTH_CONNECT.
        @Suppress("DEPRECATION")
        val picked = IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_DEVICE, Parcelable::class.java)
        val scanResult = picked as? ScanResult
        val btDevice = scanResult?.device ?: picked as? BluetoothDevice

        var address: String? = btDevice?.address
        var name: String? = scanResult?.scanRecord?.deviceName
        var associationId: Int? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            if (info != null) {
                associationId = info.id
                info.deviceMacAddress?.toString()?.let { address = it }
                if (name == null) name = info.displayName?.toString()
            }
        }

        // BluetoothAdapter.getRemoteDevice() wants upper-case "AA:BB:...". MacAddress.toString() is lower-case.
        val mac = address?.uppercase() ?: return failure(R.string.cdm_no_device)
        val deviceName = name?.takeIf { it.startsWith(OwlProtocol.NAME_PREFIX) } ?: fallbackName(mac)

        store.save(RegisteredDevice(mac, deviceName, associationId, isFake = false))
        startObserving(mac, associationId)
        return Result.success(deviceName)
    }

    override suspend fun registerFakeDevice(): Result<String> {
        if (!fakeDeviceAvailable) return failure(R.string.cdm_fake_unavailable)
        store.save(RegisteredDevice(FAKE_ADDRESS, FAKE_NAME, associationId = null, isFake = true))
        return Result.success(FAKE_NAME)
    }

    override suspend fun unregister() {
        val device = store.current()
        val cdm = cdm
        if (device != null && !device.isFake && cdm != null) {
            stopObserving(cdm, device)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Remove by id; on 31–32 registrations the id was not stored, so match by MAC.
                    cdm.myAssociations
                        .filter { it.id == device.associationId || it.deviceMacAddress?.toString().equals(device.address, ignoreCase = true) }
                        .forEach { cdm.disassociate(it.id) }
                } else {
                    @Suppress("DEPRECATION")
                    cdm.disassociate(device.address)
                }
            } catch (e: RuntimeException) {
                // Already gone (e.g. the user removed it in system settings): nothing to undo.
                Log.w(TAG, "disassociate failed", e)
            }
        }
        store.clear()
    }

    /** Asks CDM to wake [OwlCompanionService] when the pendant appears / disappears. */
    private fun startObserving(address: String, associationId: Int?) {
        val cdm = cdm ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && associationId != null) {
                cdm.startObservingDevicePresence(presenceRequest(associationId))
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(address)
            }
        } catch (e: RuntimeException) {
            // Registration still counts; the 15-min WorkManager safety net and app-start sync cover A2.
            Log.w(TAG, "startObservingDevicePresence failed", e)
        }
    }

    private fun stopObserving(cdm: CompanionDeviceManager, device: RegisteredDevice) {
        try {
            val id = device.associationId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                cdm.stopObservingDevicePresence(presenceRequest(id))
            } else {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(device.address)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopObservingDevicePresence failed", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun presenceRequest(associationId: Int) =
        ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()

    private fun <T> failure(messageRes: Int): Result<T> =
        Result.failure(IllegalStateException(appContext.getString(messageRes)))

    companion object {
        private const val TAG = "OwlCdm"
        private const val ASSOCIATE_TIMEOUT_MS = 60_000L
        const val FAKE_ADDRESS = "fake"
        const val FAKE_NAME = "Owl-FAKE"

        /** Protocol §2: the name is "Owl-" + the low 2 bytes of the device address in hex. */
        internal fun fallbackName(mac: String): String =
            OwlProtocol.NAME_PREFIX + mac.split(':').takeLast(2).joinToString("").uppercase()
    }
}
