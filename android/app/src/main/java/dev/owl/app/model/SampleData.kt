package dev.owl.app.model

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Hard-coded content from the design canvas (docs/design/android). Used by Compose previews and
 * by the in-memory stub implementations below, which let the UI run before the real data layer
 * is wired in AppContainer.
 */
object SampleData {
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(daysAgo: Long, h: Int, m: Int): Long =
        LocalDate.now().minusDays(daysAgo).atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private val library = Place("중앙도서관", 37.5665, 126.9780)
    private val home = Place("집", 37.5700, 126.9820)
    private val engineering = Place("공학관 4층", 37.5610, 126.9750)
    private val cafe = Place("학생회관 카페", 37.5620, 126.9760)

    private fun item(
        id: Long, start: Long?, minutes: Int, place: Place?, preview: String?,
        state: SegmentState = SegmentState.DONE, bookmarks: Int = 0, progress: Float? = null,
    ) = TimelineItem(
        id = id, startUnixMs = start, durationMs = minutes * 60_000L,
        durationIsEstimate = state == SegmentState.RECEIVING, place = place, preview = preview,
        state = state, receiveProgress = progress, bookmarkCount = bookmarks,
        preserveAudio = false, audioDeleted = false,
    )

    val timeline: List<TimelineItem> = listOf(
        item(1, at(1, 19, 15), 12, library, "캡스톤 발표는 데모를 먼저 보여 주고, 구조 설명은 뒤로 빼는 게 나을 것 같아."),
        item(2, at(1, 22, 41), 9, home, "내일 9시 수업이니까 펌웨어는 주말에 마저 보고, 오늘은 플래시 배선만 다시 확인하자."),
        item(3, at(0, 9, 14), 18, engineering, "그래서 페이지 폴트가 나면 커널이 디스크에서 그 페이지를 읽어 오고, 이때 TLB도 같이 갱신해야 돼요. 다음 주 퀴즈 범위는 8장까지예요."),
        item(4, at(0, 10, 1), 4, engineering, "아 맞다, 과제 마감 금요일 자정 아니고 목요일 자정이래. 조교님이 공지 다시 올린다고 했어.", bookmarks = 1),
        item(5, at(0, 12, 31), 22, cafe, "연결 간격을 15ms로 잡으면 전송은 빠른데, 평소엔 아예 끊어 두는 게 배터리엔 제일 낫대."),
        item(6, at(0, 12, 58), 6, cafe, null, state = SegmentState.TRANSCRIBING),
        item(7, at(0, 13, 6), 3, cafe, null, state = SegmentState.RECEIVING, progress = 0.4f),
    )

    val sentences: List<Sentence> = listOf(
        Sentence(0, 0, 32_000, "그러니까 이번 과제가 스케줄러 구현이잖아. 라운드 로빈이랑 MLFQ 둘 다 해야 되는 거지?"),
        Sentence(1, 32_000, 63_000, "응, 둘 다. 근데 MLFQ는 boost 주기도 바꿔 가면서 실험해서 보고서에 넣으래."),
        Sentence(2, 63_000, 102_000, "아 맞다, 과제 마감 금요일 자정 아니고 목요일 자정이래. 조교님이 공지 다시 올린다고 했어."),
        Sentence(3, 102_000, 144_000, "헐, 그럼 오늘부터 시작해야겠다. 저녁에 도서관 갈래? 나 7시 이후엔 괜찮아."),
        Sentence(4, 144_000, 194_000, "좋아. 그 전에 테스트 케이스부터 같이 짜자."),
        Sentence(5, 194_000, 252_000, "타임 퀀텀은 일단 10ms로 두고, 나중에 바꿔 보면서 비교하자."),
    )

    fun entry(id: Long): EntryDetail? {
        val base = timeline.firstOrNull { it.id == id } ?: return null
        val done = base.state == SegmentState.DONE
        return EntryDetail(
            item = if (id == 4L) base.copy(durationMs = 252_000) else base,
            sentences = if (done) sentences else emptyList(),
            bookmarkOffsetsMs = if (base.bookmarkCount > 0) listOf(67_000) else emptyList(),
            audioFiles = emptyList(),
            audioDeleteAtMs = base.startUnixMs?.plus(30L * 24 * 3600 * 1000),
        )
    }

    val standaloneBookmarks: List<StandaloneBookmark> = listOf(
        StandaloneBookmark(100, at(0, 11, 20), at(0, 11, 20), engineering.copy(namedPlaceId = 3)),
    )

    val places: List<NamedPlace> = listOf(
        NamedPlace(1, "중앙도서관", 37.5665, 126.9780, 2),
        NamedPlace(2, "집", 37.5700, 126.9820, 1),
        NamedPlace(3, "공학관 4층", 37.5610, 126.9750, 2),
        NamedPlace(4, "학생회관 카페", 37.5620, 126.9760, 3),
    )

    val device = DeviceUiState(
        registered = true, name = "Owl-3F2A", connection = Connection.CONNECTED, recordingOn = true,
        batteryPercent = 68, batteryHoursLeft = 26, storageUsedMb = 2.1, storageTotalMb = 16.0,
        pendingSegments = 4, lastSyncAtMs = at(0, 13, 2), lastSeenAtMs = at(0, 13, 2), firmwareVersion = "0.1.0",
    )
}

class StubTimelineRepository : TimelineRepository {
    override fun timeline(limit: Int, bookmarkedOnly: Boolean): Flow<List<TimelineItem>> =
        flowOf(SampleData.timeline.filter { !bookmarkedOnly || it.bookmarkCount > 0 }.takeLast(limit))

    override fun entry(id: Long): Flow<EntryDetail?> = flowOf(SampleData.entry(id))

    override fun search(filter: SearchFilter): Flow<List<SearchHit>> = flowOf(
        SampleData.timeline.filter { it.preview?.contains(filter.query) == true }
            .map { SearchHit(it, Sentence(0, 0, 0, it.preview!!)) },
    )

    override fun standaloneBookmarks(sinceMs: Long?): Flow<List<StandaloneBookmark>> =
        flowOf(SampleData.standaloneBookmarks.filter { sinceMs == null || it.sortTimeMs >= sinceMs })

    override fun deviceNotices(sinceMs: Long?): Flow<List<DeviceNotice>> = flowOf(emptyList())

    override fun searchBookmarks(filter: SearchFilter): Flow<List<StandaloneBookmark>> =
        flowOf(if (filter.query.isBlank() && filter.bookmarkedOnly) SampleData.standaloneBookmarks else emptyList())

    override fun placeNames(): Flow<List<String>> =
        flowOf(SampleData.timeline.mapNotNull { it.place?.name }.distinct())

    override fun audioUsage(): Flow<AudioUsage> = flowOf(AudioUsage(126, 412L * 1024 * 1024))
    override suspend fun setPreserveAudio(id: Long, preserve: Boolean) = Unit
    override suspend fun deleteEntry(id: Long) = Unit
    override suspend fun transcriptText(id: Long): String = SampleData.sentences.joinToString("\n") { it.text }
}

class StubDeviceController : DeviceController {
    private val _state = MutableStateFlow(SampleData.device)
    override val state: StateFlow<DeviceUiState> = _state.asStateFlow()
    override fun syncNow() = Unit
    override fun reconnect() = Unit
    override suspend fun setRecording(on: Boolean): Result<Unit> {
        _state.value = _state.value.copy(recordingOn = on)
        return Result.success(Unit)
    }
}

class StubSettingsRepository : SettingsRepository {
    private val retentionFlow = MutableStateFlow(RetentionPolicy.DAYS_30)
    private val locationFlow = MutableStateFlow(true)
    private val onboardingFlow = MutableStateFlow(false)
    private val themeFlow = MutableStateFlow(ThemeMode.DARK)
    override val retention: Flow<RetentionPolicy> = retentionFlow
    override suspend fun setRetention(policy: RetentionPolicy) { retentionFlow.value = policy }
    override val locationEnabled: Flow<Boolean> = locationFlow
    override suspend fun setLocationEnabled(enabled: Boolean) { locationFlow.value = enabled }
    override val onboardingDone: Flow<Boolean> = onboardingFlow
    override suspend fun setOnboardingDone(done: Boolean) { onboardingFlow.value = done }
    override val themeMode: Flow<ThemeMode> = themeFlow
    override suspend fun setThemeMode(mode: ThemeMode) { themeFlow.value = mode }
}

class StubDeviceRegistrar : DeviceRegistrar {
    private val name = MutableStateFlow<String?>(null)
    override val registeredName: Flow<String?> = name.map { it }
    override suspend fun startAssociation(): Result<IntentSender?> {
        name.value = "Owl-3F2A"
        return Result.success(null)
    }
    override suspend fun onAssociationResult(data: Intent?): Result<String> = Result.success("Owl-3F2A")
    override val fakeDeviceAvailable: Boolean = true
    override suspend fun registerFakeDevice(): Result<String> {
        name.value = "Owl-FAKE"
        return Result.success("Owl-FAKE")
    }
    override suspend fun unregister() { name.value = null }
}

class StubAudioPlayer : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override fun load(files: List<File>, durationMs: Long) {
        _state.value = PlayerState(loaded = true, canPlay = files.isNotEmpty(), durationMs = durationMs)
    }
    override fun play() { _state.value = _state.value.copy(playing = _state.value.canPlay) }
    override fun pause() { _state.value = _state.value.copy(playing = false) }
    override fun seekTo(positionMs: Long) { _state.value = _state.value.copy(positionMs = positionMs) }
    override fun release() = Unit
}

class StubPlacesRepository : PlacesRepository {
    private val list = MutableStateFlow(SampleData.places)
    override fun places(): Flow<List<NamedPlace>> = list
    override suspend fun nameEntryPlace(entryId: Long, name: String): Result<Unit> = Result.success(Unit)
    override suspend fun renamePlace(id: Long, name: String) {
        list.value = list.value.map { if (it.id == id) it.copy(name = name) else it }
    }
    override suspend fun deletePlace(id: Long) {
        list.value = list.value.filterNot { it.id == id }
    }
}

class StubExporter : Exporter {
    override suspend fun exportTo(uri: Uri): Result<Int> = Result.success(0)
}
