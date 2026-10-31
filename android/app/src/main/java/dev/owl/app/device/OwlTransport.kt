package dev.owl.app.device

import kotlinx.coroutines.flow.Flow

/**
 * The raw GATT link to one pendant, as bytes. Everything protocol-related ([OwlClient], sync)
 * sits on top of this, so the same code runs against the real BLE pendant ([dev.owl.app.ble.BleTransport])
 * and the in-memory [dev.owl.app.device.fake.FakeOwlDevice].
 *
 * Implementations deliver notifications in the order the device sent them.
 */
interface OwlTransport {
    /** Notifications from STATUS / CONTROL / DATA, and link loss. Hot; collect before [connect]. */
    val events: Flow<TransportEvent>

    /**
     * Connects, requests MTU 247 and 2M PHY, discovers the Owl service and subscribes to
     * STATUS, CONTROL and DATA notifications. Throws [TransportException] on failure.
     */
    suspend fun connect()

    /** Reads the STATUS characteristic (24 bytes). */
    suspend fun readStatus(): ByteArray

    /** Write With Response to CONTROL. Returns once the write is acknowledged at the ATT level. */
    suspend fun writeControl(bytes: ByteArray)

    /** Negotiated ATT MTU after [connect] (247 when everything goes to plan). */
    val mtu: Int

    suspend fun disconnect()
}

enum class Characteristic { STATUS, CONTROL, DATA }

sealed interface TransportEvent {
    class Notification(val characteristic: Characteristic, val value: ByteArray) : TransportEvent
    data object Disconnected : TransportEvent
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Creates a transport for the registered device. Real BLE or fake, chosen in AppContainer. */
fun interface TransportFactory {
    fun create(): OwlTransport?
}
