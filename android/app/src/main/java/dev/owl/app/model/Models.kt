package dev.owl.app.model

import java.io.File

/**
 * UI-facing models. The UI only sees these types plus the interfaces in [Contracts.kt];
 * Room entities and protocol types stay inside `data/`, `sync/` and `protocol/`.
 */

/** Processing state of a recording (PRD A8a). */
enum class SegmentState {
    /** Bytes are still coming from the pendant ("녹음 파일 받는 중"). */
    RECEIVING,

    /** Saved to phone storage and ACKed, waiting for STT. */
    RECEIVED,

    /** On-device STT running ("텍스트 추출 중"). */
    TRANSCRIBING,

    /** Sentences are available. */
    DONE,
}

data class Place(
    /**
     * Name the user gave this place, or null → show "알 수 없는 장소". There is no reverse
     * geocoding (PRD N8); names come only from the user ([PlacesRepository]).
     */
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    /** The named place this location falls in, null if unnamed. */
    val namedPlaceId: Long? = null,
)

/**
 * One timeline entry (PRD A7): one segment, or several adjacent segments merged because the gap
 * between them was short (see `data/EntryGrouping.kt`). Played as one continuous recording.
 */
data class TimelineItem(
    /** Entry id (the row id of its first segment); stable while the entry exists. */
    val id: Long,
    /** Real start time restored from (boot_count, uptime). Null = "시각 알 수 없음" (protocol §4). */
    val startUnixMs: Long?,
    /** Recording length. While receiving this is an estimate from the byte size. */
    val durationMs: Long,
    val durationIsEstimate: Boolean,
    val place: Place?,
    /** First sentence(s) of the transcript, null if no text yet. */
    val preview: String?,
    val state: SegmentState,
    /** 0..1 while [SegmentState.RECEIVING], else null. */
    val receiveProgress: Float?,
    val bookmarkCount: Int,
    /** True only when every segment of the entry is preserved. */
    val preserveAudio: Boolean,
    /** True when any segment's audio is gone (the entry can no longer be played). */
    val audioDeleted: Boolean,
    val segmentCount: Int = 1,
)

/**
 * A bookmark pressed while nothing was being recorded (PRD A9). Shown on its own on the
 * timeline, with low emphasis.
 */
data class StandaloneBookmark(
    val id: Long,
    /** Null = "시각 알 수 없음" (no TIME_SYNC for that boot). */
    val timeMs: Long?,
    /** Where to sort it on the timeline (= [timeMs] when known). */
    val sortTimeMs: Long,
    val place: Place?,
)

/** A place the user named (A5/A7). */
data class NamedPlace(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Timeline entries currently labelled with this name. */
    val entryCount: Int,
)

data class Sentence(
    val index: Int,
    /** Offset from the start of the entry (continuous across merged segments). */
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class EntryDetail(
    val item: TimelineItem,
    val sentences: List<Sentence>,
    /** Bookmark positions as offsets from the start of the entry. */
    val bookmarkOffsetsMs: List<Long>,
    /**
     * Raw segment files (header + Opus frames) in playing order. Empty when any of them is
     * deleted or not fully received — the entry is then not playable.
     */
    val audioFiles: List<File>,
    /** When retention will delete the audio, null if never (preserved / policy "지우지 않음"). */
    val audioDeleteAtMs: Long?,
    /**
     * (entry offset, real start time) of each part, for [clockAt]. Offsets are continuous but the
     * parts were recorded with gaps between them, so "start + offset" would drift.
     */
    val partStarts: List<Pair<Long, Long?>> = emptyList(),
) {
    /** Real time at entry offset [offsetMs], or null when the time is unknown. */
    fun clockAt(offsetMs: Long): Long? {
        val part = partStarts.lastOrNull { it.first <= offsetMs } ?: return item.startUnixMs?.plus(offsetMs)
        return part.second?.plus(offsetMs - part.first)
    }
}

enum class SearchPeriod { ALL, TODAY, LAST_7_DAYS, LAST_30_DAYS }

data class SearchFilter(
    val query: String = "",
    val period: SearchPeriod = SearchPeriod.ALL,
    /** Exact place name, null = any place. */
    val placeName: String? = null,
    val bookmarkedOnly: Boolean = false,
    val preservedOnly: Boolean = false,
)

data class SearchHit(
    val item: TimelineItem,
    /** The matching sentence; null if the hit came from filters only (empty query). */
    val sentence: Sentence?,
    /** Real time of [sentence] (or of the entry start), null if unknown. */
    val atUnixMs: Long? = item.startUnixMs,
)

/** PRD A14 / decisions.md #11. Default is [DAYS_30]. */
enum class RetentionPolicy(val days: Int?) {
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    NEVER(null),
    AFTER_TRANSCRIPTION(0),
}

/** 설정 › 테마. Default [DARK] (decisions.md #43). */
enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
}

data class AudioUsage(val entryCount: Int, val bytes: Long)

/** Power / reachability, shown separately from [recordingOn] (PRD A10). */
enum class Connection {
    /** No sync attempt yet since the app started. */
    UNKNOWN,
    CONNECTING,

    /** Last sync attempt reached the pendant. */
    CONNECTED,

    /** Sync failed after retries (decisions.md #16). Off and out-of-range look the same. */
    DISCONNECTED,

    /**
     * The pendant refused this phone: no bond on one side (protocol §2.4). Retrying does not help;
     * the user unregisters and registers again within 2 minutes of turning the pendant on.
     */
    NOT_PAIRED,
}

data class SyncProgress(
    val segmentsDone: Int,
    val segmentsTotal: Int,
    /** 0..1 for the segment currently being fetched. */
    val currentFraction: Float,
)

data class DeviceUiState(
    val registered: Boolean = false,
    /** "Owl-3F2A" */
    val name: String? = null,
    val isFake: Boolean = false,
    val connection: Connection = Connection.UNKNOWN,
    val syncing: Boolean = false,
    val progress: SyncProgress? = null,
    /** From STATUS flags bit1; null until we have read STATUS once. */
    val recordingOn: Boolean? = null,
    val storageLow: Boolean = false,
    val storageFull: Boolean = false,
    /** Computed on the phone from the voltage curve (protocol §2.1). */
    val batteryPercent: Int? = null,
    val batteryHoursLeft: Int? = null,
    val storageUsedMb: Double? = null,
    val storageTotalMb: Double? = null,
    val pendingSegments: Int? = null,
    val lastSyncAtMs: Long? = null,
    /** Last time the pendant answered; used for "13:02 이후 목걸이 응답이 없어요". */
    val lastSeenAtMs: Long? = null,
    /** "0.1.0" */
    val firmwareVersion: String? = null,
    /** True while a SET_RECORDING request is in flight. */
    val changingRecording: Boolean = false,
)

data class PlayerState(
    val loaded: Boolean = false,
    /** False when the audio file is gone (A8: player stays, play button disabled). */
    val canPlay: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)
