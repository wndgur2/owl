package dev.owl.app.data

import kotlinx.coroutines.flow.Flow

/** The one registered pendant (A1). Persisted so registration survives restarts. */
data class RegisteredDevice(
    /** Bluetooth MAC address, or "fake" for the in-memory device. */
    val address: String,
    /** Advertised name, e.g. "Owl-3F2A". */
    val name: String,
    /** CompanionDeviceManager association id (API 33+), null on 31–32 or for the fake device. */
    val associationId: Int?,
    val isFake: Boolean,
)

interface RegistrationStore {
    val device: Flow<RegisteredDevice?>
    suspend fun current(): RegisteredDevice?
    suspend fun save(device: RegisteredDevice)
    suspend fun clear()
}
