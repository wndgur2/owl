package dev.owl.app.sync

import kotlinx.coroutines.flow.StateFlow

/** Why a sync pass started; only used for logs and the notification text. */
enum class SyncTrigger { PERIODIC, MANUAL, APP_START, DEVICE_APPEARED, RECONNECT }

/** What [dev.owl.app.sync.SyncService] needs from the sync layer. */
interface SyncRunner {
    /**
     * Runs one full sync pass (protocol §5) with retries. Passes are serialised: a call while
     * another pass runs waits for it and returns its result. Returns true if the pendant was reached.
     */
    suspend fun sync(trigger: SyncTrigger): Boolean

    /** True while CDM reports the registered pendant in range (always true for the fake device). */
    val devicePresent: StateFlow<Boolean>

    fun setDevicePresent(present: Boolean)
}

/** A2: 15 minutes between passes while the pendant is present (decisions.md #15). */
const val SYNC_INTERVAL_MS: Long = 15 * 60 * 1000L
