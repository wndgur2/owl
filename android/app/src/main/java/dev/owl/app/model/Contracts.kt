package dev.owl.app.model

import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The seams between the UI and the rest of the app. ViewModels depend on these interfaces only;
 * [dev.owl.app.AppContainer] wires the real implementations.
 */

interface TimelineRepository {
    /** Newest [limit] recordings, ordered oldest → newest (newest is shown at the bottom, A7). */
    fun timeline(limit: Int, bookmarkedOnly: Boolean = false): Flow<List<TimelineItem>>

    fun entry(id: Long): Flow<EntryDetail?>

    fun search(filter: SearchFilter): Flow<List<SearchHit>>

    /** Distinct place names for the search filter. */
    fun placeNames(): Flow<List<String>>

    fun audioUsage(): Flow<AudioUsage>

    suspend fun setPreserveAudio(id: Long, preserve: Boolean)

    /** Deletes the recording, its transcript and its audio file. */
    suspend fun deleteEntry(id: Long)

    /** Plain text of the transcript, for "텍스트 복사". */
    suspend fun transcriptText(id: Long): String
}

interface DeviceController {
    val state: StateFlow<DeviceUiState>

    /** "지금 동기화" (A2a). Returns immediately; progress shows in [state]. */
    fun syncNow()

    /** Connect button when disconnected, and on every app start (A2a, decisions.md #16). */
    fun reconnect()

    /** Recording switch on the Device screen (SET_RECORDING). */
    suspend fun setRecording(on: Boolean): Result<Unit>
}

interface SettingsRepository {
    val retention: Flow<RetentionPolicy>
    suspend fun setRetention(policy: RetentionPolicy)

    /** A5 on/off. When on, LocationRecorder runs (needs "항상 허용"). */
    val locationEnabled: Flow<Boolean>
    suspend fun setLocationEnabled(enabled: Boolean)

    val onboardingDone: Flow<Boolean>
    suspend fun setOnboardingDone(done: Boolean)
}

/** A1: one-time pendant registration through CompanionDeviceManager. */
interface DeviceRegistrar {
    /** Registered device name ("Owl-3F2A"), null if none. */
    val registeredName: Flow<String?>

    /**
     * Starts CDM association. The returned sender opens the system chooser; pass its result
     * intent to [onAssociationResult]. Returns null when no system UI is needed (fake device).
     */
    suspend fun startAssociation(): Result<IntentSender?>

    suspend fun onAssociationResult(data: Intent?): Result<String>

    suspend fun unregister()
}

/** Plays one stored segment file (A8). One instance per Entry screen. */
interface AudioPlayer {
    val state: StateFlow<PlayerState>

    /** [file] null → player shown but disabled (audio deleted). */
    fun load(file: File?, durationMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

/** A13: writes all records (text, time, place) to a user-chosen document. */
interface Exporter {
    suspend fun exportTo(uri: android.net.Uri): Result<Int>
}
