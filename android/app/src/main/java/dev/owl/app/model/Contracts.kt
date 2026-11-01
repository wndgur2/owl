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
    /** Newest [limit] entries, ordered oldest → newest (newest is shown at the bottom, A7). */
    fun timeline(limit: Int, bookmarkedOnly: Boolean = false): Flow<List<TimelineItem>>

    /**
     * Bookmarks outside any recording (A9) with sort time ≥ [sinceMs] (null = all), oldest first.
     * The timeline merges them in by sort time.
     */
    fun standaloneBookmarks(sinceMs: Long?): Flow<List<StandaloneBookmark>>

    /** Device events (not bookmarks) with sort time ≥ [sinceMs] (null = all), oldest first. */
    fun deviceNotices(sinceMs: Long?): Flow<List<DeviceNotice>>

    /** Standalone bookmarks matching the search filters (period, place); empty if the query is not blank. */
    fun searchBookmarks(filter: SearchFilter): Flow<List<StandaloneBookmark>>

    fun entry(id: Long): Flow<EntryDetail?>

    fun search(filter: SearchFilter): Flow<List<SearchHit>>

    /** Names of the user's named places, for the search filter. */
    fun placeNames(): Flow<List<String>>

    fun audioUsage(): Flow<AudioUsage>

    /** Applies to every segment of the entry. */
    suspend fun setPreserveAudio(id: Long, preserve: Boolean)

    /** Deletes the entry: all its segments, their transcripts and audio files. */
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

    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
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

    /** Debug builds only: the in-memory FakeOwlDevice can be registered instead of a real pendant. */
    val fakeDeviceAvailable: Boolean

    /** Registers the fake pendant ("Owl-FAKE") so the whole pipeline runs on an emulator. */
    suspend fun registerFakeDevice(): Result<String>

    /** Removes the CDM association and the stored device. Recordings already on the phone stay. */
    suspend fun unregister()
}

/** Places the user named (A5/A7). No network geocoding (PRD N8). */
interface PlacesRepository {
    fun places(): Flow<List<NamedPlace>>

    /**
     * "이 장소 이름 붙이기" from entry detail: names the entry's location. If it already falls in a
     * named place, that place is renamed. The name applies to every location within
     * `PLACE_RADIUS_M` (past and future entries). Fails if the entry has no location.
     */
    suspend fun nameEntryPlace(entryId: Long, name: String): Result<Unit>

    suspend fun renamePlace(id: Long, name: String)

    /** Entries in it go back to "알 수 없는 장소". */
    suspend fun deletePlace(id: Long)
}

/** Plays one timeline entry (A8). One instance per Entry screen. */
interface AudioPlayer {
    val state: StateFlow<PlayerState>

    /**
     * [files] are the entry's segment files, played back to back as one recording.
     * Empty → player shown but disabled (audio deleted), [durationMs] still drives the seek bar.
     */
    fun load(files: List<File>, durationMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 0.5–2.0×; shown as the speed label in the player. */
    fun setSpeed(speed: Float) = Unit
    fun release()
}

/** A13: writes all records (text, time, place) to a user-chosen document. */
interface Exporter {
    suspend fun exportTo(uri: android.net.Uri): Result<Int>
}
