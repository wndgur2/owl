package dev.owl.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.owl.app.device.Characteristic
import dev.owl.app.device.NotBondedException
import dev.owl.app.device.OwlTransport
import dev.owl.app.device.TransportEvent
import dev.owl.app.device.TransportException
import dev.owl.app.protocol.OwlProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-pendant [OwlTransport]: an Android GATT client for one Owl at [address].
 *
 * Android BLE basics, for readers new to it:
 * - **GATT** is the request/response layer on top of a BLE link. The pendant is the GATT *server*
 *   (it owns the Owl service and its characteristics); the phone is the *client*.
 * - Android's `BluetoothGatt` accepts **one outstanding operation at a time** (read, write,
 *   descriptor write, MTU request, service discovery). Starting a second one before the first
 *   one's callback arrives makes it silently fail (the call returns false). So every operation
 *   here goes through [gattOp], which holds a [Mutex] until the matching callback arrives.
 * - Callbacks arrive on a Binder thread, not on the caller's coroutine. [Pending] bridges the two
 *   with a [CompletableDeferred]: the coroutine suspends on it, the callback completes it.
 * - **Notifications** are server-initiated pushes. They only start after the client writes
 *   0x0001 to the characteristic's CCCD descriptor (and calls `setCharacteristicNotification`,
 *   which only tells the local stack to forward them to us).
 *
 * The connection is short-lived: the sync engine calls [connect], runs one pass, then [disconnect]
 * (protocol §5, decisions.md #15). Each [connect] creates a fresh `BluetoothGatt`.
 *
 * Security (protocol §2.4): the pendant answers CONTROL and the CCCDs only on an encrypted link.
 * The phone bonds once, at registration ([BondManager]); on every connection the pendant sends a
 * Security Request and Android encrypts with the stored key. If something is refused, Android's
 * own retry (BluetoothGatt re-tries a status 5/15 operation after pairing/encrypting) ends with
 * status 137 (GATT_AUTH_FAIL), which becomes [NotBondedException]: the sync coordinator stops
 * retrying and shows "다시 등록".
 */
class BleTransport(context: Context, private val address: String) : OwlTransport {

    private val appContext = context.applicationContext

    /**
     * Why a Channel and not a SharedFlow: DATA notifications arrive back to back (~236-byte
     * chunks, up to tens of KB/s), and every one of them matters (a missing chunk means a CRC
     * failure and a re-fetch). A `Channel(UNLIMITED)` never drops and also buffers events that
     * arrive before anyone collects. A `MutableSharedFlow` drops emissions when it has no
     * subscriber and can suspend/drop when its buffer is full. Trade-off: a channel delivers each
     * event to exactly one collector, so only one collector (the sync engine) may consume [events].
     *
     * The channel is never closed, so the same transport can [connect] again after a disconnect.
     */
    private val eventChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventChannel.receiveAsFlow()

    @Volatile private var negotiatedMtu = DEFAULT_MTU
    override val mtu: Int get() = negotiatedMtu

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var callback: GattCallback? = null
    @Volatile private var characteristics: Map<Characteristic, BluetoothGattCharacteristic> = emptyMap()

    /** Serialises GATT operations (Android allows one in flight). */
    private val opMutex = Mutex()

    /** The operation currently waiting for its callback, if any. */
    private val pending = AtomicReference<Pending?>(null)

    private enum class Kind { CONNECT, MTU, DISCOVER, READ, WRITE, DESCRIPTOR }

    private class Pending(val kind: Kind, val target: Any? = null) {
        val result = CompletableDeferred<ByteArray?>()
    }

    // --- OwlTransport ------------------------------------------------------------------------

    // Every BLE call below sits behind requireConnectPermission() at the top of this method.
    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        requireConnectPermission()
        closeGatt() // a previous link, if the caller forgot to disconnect

        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportException("Bluetooth not available")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is off")
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportException("bad address $address", e)
        }
        // No bond on our side (removed in system settings, or registered before bonding existed):
        // connecting would make Android pop a pairing dialog during a background sync, which the
        // pendant refuses outside its window anyway.
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw NotBondedException("phone has no bond with $address")

        negotiatedMtu = DEFAULT_MTU
        try {
            // 1. Link. autoConnect=false = direct connection attempt, fast but gives up (status 133)
            //    when the pendant is not advertising; the sync engine retries (decisions.md #16).
            //    TRANSPORT_LE forces BLE even if the stack thinks the device is dual-mode.
            opMutex.withLock {
                val cb = GattCallback()
                callback = cb
                awaitPending(Pending(Kind.CONNECT), CONNECT_TIMEOUT_MS) {
                    val g = device.connectGatt(appContext, false, cb, BluetoothDevice.TRANSPORT_LE)
                    gatt = g
                    g != null
                }
            }
            val g = gatt ?: throw TransportException("gatt closed")

            // 2. MTU 247 → 244-byte ATT payload → 236-byte DATA chunks (protocol §2.3).
            //    The pendant may agree to less; [mtu] reports what we actually got.
            gattOp(Kind.MTU) { it.requestMtu(OwlProtocol.ATT_MTU) }

            // 3. 2M PHY doubles the raw radio rate. This is a link-layer request, not a GATT
            //    operation, so it does not block the queue; the result only shows in onPhyUpdate.
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)

            // 4. Find the Owl service and its three characteristics.
            gattOp(Kind.DISCOVER) { it.discoverServices() }
            val service = g.getService(OwlProtocol.SERVICE_UUID)
                ?: throw TransportException("Owl service not found")
            characteristics = mapOf(
                Characteristic.STATUS to OwlProtocol.STATUS_UUID,
                Characteristic.CONTROL to OwlProtocol.CONTROL_UUID,
                Characteristic.DATA to OwlProtocol.DATA_UUID,
            ).mapValues { (name, uuid) ->
                service.getCharacteristic(uuid) ?: throw TransportException("$name characteristic missing")
            }

            // 5. Subscribe, one CCCD write at a time.
            for (name in listOf(Characteristic.STATUS, Characteristic.CONTROL, Characteristic.DATA)) {
                enableNotifications(g, characteristics.getValue(name))
            }

            // 6. Short connection interval while we transfer (protocol §2: 15–30 ms). The link
            //    only lives for one sync pass, so we ask for it for the whole connection.
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (e: Throwable) {
            closeGatt()
            throw if (e is TransportException) e else TransportException("connect failed: ${e.message}", e)
        }
    }

    // requireConnectPermission() first; readCharacteristic needs BLUETOOTH_CONNECT.
    @SuppressLint("MissingPermission")
    override suspend fun readStatus(): ByteArray {
        requireConnectPermission()
        val ch = characteristic(Characteristic.STATUS)
        return gattOp(Kind.READ, ch) { it.readCharacteristic(ch) } ?: ByteArray(0)
    }

    // requireConnectPermission() first; writeCharacteristic needs BLUETOOTH_CONNECT.
    @SuppressLint("MissingPermission")
    override suspend fun writeControl(bytes: ByteArray) {
        requireConnectPermission()
        val ch = characteristic(Characteristic.CONTROL)
        // Write With Response: the pendant's ATT layer acknowledges it, then onCharacteristicWrite fires.
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gattOp(Kind.WRITE, ch) { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = type
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }
        }
    }

    override suspend fun disconnect() {
        closeGatt()
    }

    // --- internals -----------------------------------------------------------------------------

    // Called from connect() after requireConnectPermission().
    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(ch, true)) {
            throw TransportException("setCharacteristicNotification failed for ${ch.uuid}")
        }
        val cccd = ch.getDescriptor(OwlProtocol.CCCD_UUID)
            ?: throw TransportException("CCCD missing on ${ch.uuid}")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // 0x01 0x00
        gattOp(Kind.DESCRIPTOR, cccd) { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    gg.writeDescriptor(cccd)
                }
            }
        }
    }

    private fun characteristic(name: Characteristic): BluetoothGattCharacteristic =
        characteristics[name] ?: throw TransportException("not connected")

    /** Runs one GATT operation: start it, then suspend until its callback (or a timeout). */
    private suspend fun gattOp(
        kind: Kind,
        target: Any? = null,
        timeoutMs: Long = OP_TIMEOUT_MS,
        start: (BluetoothGatt) -> Boolean,
    ): ByteArray? = opMutex.withLock {
        val g = gatt ?: throw TransportException("not connected")
        awaitPending(Pending(kind, target), timeoutMs) { start(g) }
    }

    /** Caller holds [opMutex]. */
    private suspend fun awaitPending(p: Pending, timeoutMs: Long, start: () -> Boolean): ByteArray? {
        pending.set(p)
        try {
            if (!start()) throw TransportException("${p.kind} could not be started (GATT busy or link down)")
            return withTimeout(timeoutMs) { p.result.await() }
        } catch (e: TimeoutCancellationException) {
            throw TransportException("${p.kind} timed out after $timeoutMs ms", e)
        } finally {
            pending.compareAndSet(p, null)
        }
    }

    /** Completes the waiting op if it is of [kind] (and for [target], when given). */
    private fun complete(kind: Kind, target: Any?, status: Int, value: ByteArray? = null) {
        val p = pending.get() ?: return
        if (p.kind != kind || (p.target != null && p.target !== target)) return
        if (!pending.compareAndSet(p, null)) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            p.result.complete(value)
        } else if (isAuthFailure(status)) {
            p.result.completeExceptionally(NotBondedException("$kind refused, GATT status $status (not bonded)"))
        } else {
            p.result.completeExceptionally(TransportException("$kind failed, GATT status $status"))
        }
    }

    private fun failPending(reason: String) = failPending(TransportException(reason))

    private fun failPending(e: TransportException) {
        pending.getAndSet(null)?.result?.completeExceptionally(e)
    }

    private fun closeGatt() {
        callback?.active = false
        callback = null
        val g = gatt
        gatt = null
        characteristics = emptyMap()
        failPending("disconnected")
        if (g != null) closeQuietly(g)
    }

    /**
     * disconnect() drops the link; close() frees the client slot (Android has a small, shared
     * number of them, and leaking one eventually makes every connect fail with status 133).
     */
    @SuppressLint("MissingPermission") // guarded by hasConnectPermission() just below
    private fun closeQuietly(g: BluetoothGatt) {
        if (!hasConnectPermission()) return // permission revoked: the system already tore it down
        try {
            g.disconnect()
            g.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close failed", e)
        }
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requireConnectPermission() {
        if (!hasConnectPermission()) throw TransportException("BLUETOOTH_CONNECT permission not granted")
    }

    /**
     * One instance per connection. After [closeGatt] it is marked inactive, so late callbacks
     * from an old BluetoothGatt cannot complete a newer connection's operations.
     *
     * Ordering: the stack delivers callbacks through a one-way Binder interface, which Android
     * delivers one at a time and in order for the same Binder object, so notifications reach
     * [eventChannel] in the order the pendant sent them.
     */
    private inner class GattCallback : BluetoothGattCallback() {
        @Volatile var active = true
        private var connected = false

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!active) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                complete(Kind.CONNECT, null, status)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // status 133 (GATT_ERROR) here usually means "pendant not reachable".
                Log.i(TAG, "disconnected, status $status")
                val wasConnected = connected
                connected = false
                active = false
                if (gatt === g) {
                    gatt = null
                    callback = null
                    characteristics = emptyMap()
                    closeQuietly(g)
                }
                failPending(
                    if (isAuthDisconnect(status)) NotBondedException("pendant dropped the link: not bonded (status $status)")
                    else TransportException("link lost (GATT status $status)"),
                )
                if (wasConnected) eventChannel.trySend(TransportEvent.Disconnected)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!active) return
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            Log.i(TAG, "MTU $mtu (status $status)")
            // A refused MTU is not fatal: the link still works with the default MTU, and [mtu]
            // tells the sync engine what it got.
            complete(Kind.MTU, null, BluetoothGatt.GATT_SUCCESS)
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY tx=$txPhy rx=$rxPhy (status $status)")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (active) complete(Kind.DISCOVER, null, status)
        }

        // API 33+: the value comes as a parameter (no shared mutable characteristic.value).
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (active) complete(Kind.READ, ch, status, value)
        }

        // API 31–32 only; on 33+ the system calls the overload above instead.
        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || !active) return
            complete(Kind.READ, ch, status, ch.value?.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (active) complete(Kind.WRITE, ch, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (active) complete(Kind.DESCRIPTOR, d, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (active) emitNotification(ch, value)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || !active) return
            // Pre-33 the value lives in the shared characteristic object and the next
            // notification overwrites it, so copy now.
            emitNotification(ch, ch.value?.copyOf() ?: return)
        }

        private fun emitNotification(ch: BluetoothGattCharacteristic, value: ByteArray) {
            val name = when (ch.uuid) {
                OwlProtocol.STATUS_UUID -> Characteristic.STATUS
                OwlProtocol.CONTROL_UUID -> Characteristic.CONTROL
                OwlProtocol.DATA_UUID -> Characteristic.DATA
                else -> return
            }
            eventChannel.trySend(TransportEvent.Notification(name, value))
        }
    }

    companion object {
        private const val TAG = "OwlBle"

        /** BLE default ATT MTU before negotiation. */
        const val DEFAULT_MTU = 23
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val OP_TIMEOUT_MS = 10_000L

        /** ATT Insufficient Authentication / Encryption, and Android's GATT_AUTH_FAIL after its retry. */
        private val AUTH_FAILURE_STATUSES = setOf(0x05, 0x0F, 0x89)

        /**
         * HCI disconnect reasons that mean "no shared key": 0x05 Authentication Failure (the pendant
         * drops a link that is still unencrypted after 60 s, §2.4), 0x06 PIN or Key Missing (the
         * pendant no longer has our bond).
         */
        private val AUTH_DISCONNECT_REASONS = setOf(0x05, 0x06)

        fun isAuthFailure(gattStatus: Int): Boolean = gattStatus in AUTH_FAILURE_STATUSES

        fun isAuthDisconnect(status: Int): Boolean = status in AUTH_DISCONNECT_REASONS
    }
}
