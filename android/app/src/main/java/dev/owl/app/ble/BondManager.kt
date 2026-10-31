package dev.owl.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bonding with the pendant (protocol §2.4, decisions.md #33).
 *
 * BLE words, for readers new to them:
 * - **Pairing** = the two sides agree on an encryption key. The pendant has no screen or keypad,
 *   so it is "Just Works": Android asks the user to confirm, no PIN.
 * - **Bonding** = both sides *store* that key, so later connections encrypt without asking again.
 *   The pendant only answers CONTROL and DATA on an encrypted link from its bonded phone.
 *
 * [createBond] starts pairing; the result arrives later as an ACTION_BOND_STATE_CHANGED broadcast.
 * When our app calls createBond() within 10 minutes of the CDM association, Android's Settings
 * confirms the Just Works pairing without a second dialog (AOSP BluetoothPairingRequest /
 * AdapterService.canBondWithoutDialog).
 */
interface Bonder {
    /** Bonds with [address], or returns at once if already bonded. Fails if the pendant refuses. */
    suspend fun bond(address: String): Result<Unit>

    /** Removes the phone's side of the bond. Best effort; false if Android would not let us. */
    fun removeBond(address: String, associationId: Int?): Boolean
}

class BondManager(context: Context) : Bonder {
    private val appContext = context.applicationContext

    // Every call below sits behind hasConnectPermission().
    @SuppressLint("MissingPermission")
    override suspend fun bond(address: String): Result<Unit> {
        if (!hasConnectPermission()) return Result.failure(BondException("BLUETOOTH_CONNECT permission not granted", noPermission = true))
        val device = remoteDevice(address) ?: return Result.failure(BondException("Bluetooth not available"))
        if (device.bondState == BluetoothDevice.BOND_BONDED) return Result.success(Unit)

        val outcome = CompletableDeferred<BondStates.Outcome>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (!d?.address.equals(address, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val reason = intent.getIntExtra(EXTRA_UNBOND_REASON, -1)
                BondStates.outcome(state)?.let {
                    Log.i(TAG, "bond state $state (reason $reason)")
                    outcome.complete(it)
                }
            }
        }
        // The broadcast comes from the Bluetooth process (uid 1002), not "system": a NOT_EXPORTED
        // receiver would never see it. It is a protected broadcast, so no other app can fake it.
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            if (!device.createBond()) {
                // Also false when a bond completed between our check and the call.
                return if (device.bondState == BluetoothDevice.BOND_BONDED) Result.success(Unit)
                else Result.failure(BondException("createBond refused by the system"))
            }
            return when (withTimeoutOrNull(BOND_TIMEOUT_MS) { outcome.await() }) {
                BondStates.Outcome.BONDED -> Result.success(Unit)
                BondStates.Outcome.FAILED -> Result.failure(BondException("pendant refused pairing"))
                null -> Result.failure(BondException("pairing timed out"))
            }
        } finally {
            appContext.unregisterReceiver(receiver)
        }
    }

    // Guarded by hasConnectPermission().
    @SuppressLint("MissingPermission")
    override fun removeBond(address: String, associationId: Int?): Boolean {
        if (!hasConnectPermission()) return false
        // API 36: the supported way, for devices we hold a CDM association for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && associationId != null) {
            val cdm = appContext.getSystemService(CompanionDeviceManager::class.java)
            try {
                if (cdm?.removeBond(associationId) == true) return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "CDM removeBond failed", e)
            }
        }
        // API 31-35: BluetoothDevice.removeBond() is @SystemApi but only needs BLUETOOTH_CONNECT,
        // and it is in no hidden-API deny list (AOSP hiddenapi flags: "sdk"), so reflection works.
        val device = remoteDevice(address) ?: return false
        if (device.bondState == BluetoothDevice.BOND_NONE) return true
        return try {
            device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "removeBond via reflection failed", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "removeBond not allowed", e)
            false
        }
    }

    private fun remoteDevice(address: String): BluetoothDevice? {
        val adapter: BluetoothAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "OwlBond"

        /** Covers the user reading the confirmation dialog, plus SMP's own 30 s timeout. */
        const val BOND_TIMEOUT_MS = 60_000L

        /** BluetoothDevice.EXTRA_UNBOND_REASON is @SystemApi; the string is stable (AOSP). Logged only. */
        private const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
    }
}

class BondException(message: String, val noPermission: Boolean = false) : Exception(message)

/** Pure part of [BondManager.bond]: what a bond-state broadcast means for the pairing we started. */
object BondStates {
    enum class Outcome { BONDED, FAILED }

    /** BOND_BONDING is progress, not an answer. BOND_NONE after createBond() = refused / cancelled / failed. */
    fun outcome(state: Int): Outcome? = when (state) {
        BluetoothDevice.BOND_BONDED -> Outcome.BONDED
        BluetoothDevice.BOND_NONE -> Outcome.FAILED
        else -> null
    }
}
