package dev.owl.app.data

import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.data.db.SegmentEntity
import dev.owl.app.data.db.SegmentRow
import dev.owl.app.data.db.SentenceEntity
import dev.owl.app.model.AudioUsage
import dev.owl.app.model.EntryDetail
import dev.owl.app.model.Place
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SearchFilter
import dev.owl.app.model.SearchHit
import dev.owl.app.model.SearchPeriod
import dev.owl.app.model.SegmentState
import dev.owl.app.model.Sentence
import dev.owl.app.model.TimelineItem
import dev.owl.app.model.TimelineRepository
import dev.owl.app.retention.RetentionRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRepositoryImpl(
    private val db: OwlDatabase,
    private val retention: Flow<RetentionPolicy>,
    private val clock: () -> Long = System::currentTimeMillis,
) : TimelineRepository {
    private val segments = db.segments()
    private val sentences = db.sentences()

    override fun timeline(limit: Int, bookmarkedOnly: Boolean): Flow<List<TimelineItem>> =
        segments.timeline(limit, bookmarkedOnly).map { rows -> rows.asReversed().map { it.toItem() } }

    override fun entry(id: Long): Flow<EntryDetail?> = segments.observeRow(id).flatMapLatest { row ->
        if (row == null) return@flatMapLatest flowOf(null)
        val s = row.segment
        val bookmarks = if (s.bootCount != null && s.startUptimeMs != null) {
            db.events().bookmarks(s.deviceAddress, s.bootCount, s.startUptimeMs, s.startUptimeMs + (s.durationMs ?: 0))
        } else {
            flowOf(emptyList())
        }
        combine(sentences.observeFor(id), bookmarks, retention) { sent, marks, policy ->
            EntryDetail(
                item = row.toItem(),
                sentences = sent.map { it.toModel() },
                bookmarkOffsetsMs = marks.map { it.uptimeMs - s.startUptimeMs!! },
                audioFiles = listOfNotNull(s.filePath?.let(::File)?.takeIf { !s.audioDeleted && s.state != SegmentState.RECEIVING && it.exists() }),
                audioDeleteAtMs = RetentionRules.deleteAt(policy, s),
            )
        }
    }

    override fun search(filter: SearchFilter): Flow<List<SearchHit>> {
        val rows = segments.filtered(sinceMs(filter.period), filter.placeName, filter.bookmarkedOnly, filter.preservedOnly)
        val q = filter.query.trim()
        if (q.isEmpty()) return rows.map { list -> list.map { SearchHit(it.toItem(), null) } }
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return combine(rows, sentences.search(pattern)) { list, hits ->
            val byId = list.associateBy { it.segment.rowId }
            hits.mapNotNull { h -> byId[h.segmentRowId]?.let { h to it } }
                .sortedWith(compareByDescending<Pair<SentenceEntity, SegmentRow>> { it.second.segment.sortTimeMs }.thenBy { it.first.idx })
                .map { (sentence, row) -> SearchHit(row.toItem(), sentence.toModel()) }
        }
    }

    private fun sinceMs(period: SearchPeriod): Long {
        val now = clock()
        return when (period) {
            SearchPeriod.ALL -> Long.MIN_VALUE
            SearchPeriod.TODAY -> LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            SearchPeriod.LAST_7_DAYS -> now - 7 * DAY_MS
            SearchPeriod.LAST_30_DAYS -> now - 30 * DAY_MS
        }
    }

    override fun standaloneBookmarks(sinceMs: Long?): Flow<List<dev.owl.app.model.StandaloneBookmark>> = flowOf(emptyList())

    override fun searchBookmarks(filter: SearchFilter): Flow<List<dev.owl.app.model.StandaloneBookmark>> = flowOf(emptyList())

    override fun placeNames(): Flow<List<String>> = segments.placeNames()

    override fun audioUsage(): Flow<AudioUsage> = segments.audioUsage().map { AudioUsage(it.entryCount, it.bytes) }

    override suspend fun setPreserveAudio(id: Long, preserve: Boolean) = segments.setPreserve(id, preserve)

    override suspend fun deleteEntry(id: Long) {
        val s = segments.get(id) ?: return
        s.filePath?.let { File(it).delete() }
        segments.delete(id) // sentences go with it (FK cascade)
    }

    override suspend fun transcriptText(id: Long): String = sentences.forSegment(id).joinToString("\n") { it.text }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}

private fun SentenceEntity.toModel() = Sentence(idx, startMs, endMs, text)

internal fun SegmentRow.toItem(): TimelineItem {
    val s = segment
    val receiving = s.state == SegmentState.RECEIVING
    return TimelineItem(
        id = s.rowId,
        startUnixMs = s.startUnixMs,
        durationMs = s.durationMs ?: estimateDurationMs(s),
        durationIsEstimate = s.durationMs == null,
        place = s.latitude?.let { Place(s.placeName, it, s.longitude!!) },
        preview = preview,
        state = s.state,
        receiveProgress = if (receiving) (if (s.sizeBytes > 0) s.receivedBytes.toFloat() / s.sizeBytes else 0f) else null,
        bookmarkCount = bookmarkCount,
        preserveAudio = s.preserveAudio,
        audioDeleted = s.audioDeleted,
    )
}

/** Before the whole segment is here: 16 kbps Opus is 2 bytes/ms plus a 2-byte length per frame. */
internal fun estimateDurationMs(s: SegmentEntity): Long {
    val frameMs = (s.frameMs ?: 20).coerceAtLeast(1)
    val payload = (s.sizeBytes - 32).coerceAtLeast(0)
    return (payload / (2.0 + 2.0 / frameMs)).toLong()
}
