package dev.owl.app.data

import dev.owl.app.data.db.EventEntity
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.data.db.PlaceEntity
import dev.owl.app.data.db.SegmentEntity
import dev.owl.app.data.db.SegmentRow
import dev.owl.app.data.db.SentenceEntity
import dev.owl.app.data.db.StandaloneRow
import dev.owl.app.model.AudioUsage
import dev.owl.app.model.EntryDetail
import dev.owl.app.model.Place
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SearchFilter
import dev.owl.app.model.SearchHit
import dev.owl.app.model.SearchPeriod
import dev.owl.app.model.SegmentState
import dev.owl.app.model.Sentence
import dev.owl.app.model.StandaloneBookmark
import dev.owl.app.model.TimelineItem
import dev.owl.app.model.TimelineRepository
import dev.owl.app.retention.RetentionRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Timeline entries (merged segments, see [EntryGrouping]) assembled from Room rows. Sentence and
 * bookmark times are mapped onto one continuous entry timeline: segment N starts where segment
 * N−1's audio ends, gaps between them are skipped (that is how the player plays them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRepositoryImpl(
    private val db: OwlDatabase,
    private val retention: Flow<RetentionPolicy>,
    /** Runs after a deletion so neighbouring entries are regrouped. */
    private val reindex: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : TimelineRepository {
    private val segments = db.segments()
    private val sentences = db.sentences()
    private val events = db.events()

    override fun timeline(limit: Int, bookmarkedOnly: Boolean): Flow<List<TimelineItem>> =
        combine(segments.timelineSegments(limit, bookmarkedOnly), events.bookmarkCounts()) { rows, counts ->
            val byKey = counts.associate { it.k to it.n }
            assemble(rows).map { it.toItem(byKey[it.key] ?: 0) }
        }

    override fun entry(id: Long): Flow<EntryDetail?> = segments.observeEntry(id).flatMapLatest { rows ->
        if (rows.isEmpty()) return@flatMapLatest flowOf(null)
        val entry = assemble(rows).first()
        combine(
            sentences.observeForSegments(rows.map { it.segment.rowId }),
            events.entryBookmarks(entry.key),
            retention,
        ) { sent, marks, policy ->
            EntryDetail(
                item = entry.toItem(marks.size),
                sentences = entry.sentences(sent),
                bookmarkOffsetsMs = marks.map { entry.offsetOf(it) },
                audioFiles = entry.audioFiles(),
                audioDeleteAtMs = entry.parts.mapNotNull { RetentionRules.deleteAt(policy, it) }.minOrNull(),
                partStarts = entry.partStarts(),
            )
        }
    }

    override fun search(filter: SearchFilter): Flow<List<SearchHit>> {
        val entries = combine(segments.allAudioRows(), events.bookmarkCounts()) { rows, counts ->
            val byKey = counts.associate { it.k to it.n }
            assemble(rows).filter { e -> matches(e, byKey[e.key] ?: 0, filter) }.map { it to (byKey[it.key] ?: 0) }
        }
        val q = filter.query.trim()
        if (q.isEmpty()) {
            return entries.map { list -> list.asReversed().map { (e, n) -> SearchHit(e.toItem(n), null) } }
        }
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return combine(entries, sentences.search(pattern)) { list, hits ->
            val bySegment = HashMap<Long, Pair<Entry, Int>>()
            list.forEach { pair -> pair.first.parts.forEach { bySegment[it.rowId] = pair } }
            hits.mapNotNull { h -> bySegment[h.segmentRowId]?.let { h to it } }
                .sortedWith(compareByDescending<Pair<SentenceEntity, Pair<Entry, Int>>> { it.second.first.sortTimeMs }.thenBy { it.second.first.offsetOfSentence(it.first) })
                .map { (sentence, pair) ->
                    val (e, n) = pair
                    val hit = e.sentence(sentence)
                    SearchHit(e.toItem(n), hit, e.clockAt(hit.startMs))
                }
        }
    }

    private fun matches(e: Entry, bookmarks: Int, f: SearchFilter): Boolean =
        e.sortTimeMs >= sinceMs(f.period) &&
            (f.placeName == null || e.place?.name == f.placeName) &&
            (!f.bookmarkedOnly || bookmarks > 0) &&
            (!f.preservedOnly || e.parts.all { it.preserveAudio })

    override fun standaloneBookmarks(sinceMs: Long?): Flow<List<StandaloneBookmark>> =
        events.standaloneBookmarks(sinceMs ?: Long.MIN_VALUE).mapLatest { rows -> withPlaces(rows) }

    override fun searchBookmarks(filter: SearchFilter): Flow<List<StandaloneBookmark>> {
        // Standalone bookmarks have no text, and nothing to preserve.
        if (filter.query.isNotBlank() || !filter.bookmarkedOnly || filter.preservedOnly) return flowOf(emptyList())
        return standaloneBookmarks(sinceMs(filter.period).takeIf { it != Long.MIN_VALUE })
            .map { list -> list.filter { filter.placeName == null || it.place?.name == filter.placeName } }
    }

    /** A standalone bookmark's place: the GPS fix nearest its time, named if inside a named place. */
    private suspend fun withPlaces(rows: List<StandaloneRow>): List<StandaloneBookmark> {
        val places: List<PlaceEntity> = db.places().all()
        return rows.map { r ->
            val place = r.timeMs?.let { t ->
                db.locations().nearest(t, t - PlaceResolver.MAX_GAP_MS, t + PlaceResolver.MAX_GAP_MS)?.let { fix ->
                    val named = PlaceResolver.nearestPlace(places, fix.latitude, fix.longitude)
                    Place(named?.name, fix.latitude, fix.longitude, named?.rowId)
                }
            }
            StandaloneBookmark(r.id, r.timeMs, r.sortTimeMs, place)
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

    override fun placeNames(): Flow<List<String>> = db.places().names()

    override fun audioUsage(): Flow<AudioUsage> = segments.audioUsage().map { AudioUsage(it.entryCount, it.bytes) }

    override suspend fun setPreserveAudio(id: Long, preserve: Boolean) = segments.setPreserveEntry(id, preserve)

    override suspend fun deleteEntry(id: Long) {
        segments.entrySegments(id).forEach { s -> s.filePath?.let { File(it).delete() } }
        events.deleteEntryBookmarks(id)
        segments.deleteEntry(id) // sentences go with it (FK cascade)
        reindex()
    }

    override suspend fun transcriptText(id: Long): String =
        segments.entrySegments(id).flatMap { sentences.forSegment(it.rowId) }.joinToString("\n") { it.text }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}

/** Rows (ordered by time) → entries, keeping segment order inside each entry. */
internal fun assemble(rows: List<SegmentRow>): List<Entry> =
    rows.groupBy { it.segment.entryKey ?: it.segment.rowId }
        .map { (key, list) -> Entry(key, list.sortedBy { it.segment.segmentId }) }
        .sortedWith(compareBy({ it.sortTimeMs }, { it.parts.first().segmentId }))

internal class Entry(val key: Long, val rows: List<SegmentRow>) {
    val parts: List<SegmentEntity> = rows.map { it.segment }
    private val durations = parts.map { it.durationMs ?: estimateDurationMs(it) }

    /** Offset of each part on the continuous entry timeline. */
    private val starts = durations.runningFold(0L) { acc, d -> acc + d }

    val sortTimeMs: Long get() = parts.first().sortTimeMs

    val place: Place? get() = parts.firstOrNull { it.latitude != null }?.let { Place(it.placeName, it.latitude!!, it.longitude!!, it.placeId) }

    val state: SegmentState
        get() = when {
            parts.any { it.state == SegmentState.RECEIVING } -> SegmentState.RECEIVING
            parts.any { it.state == SegmentState.TRANSCRIBING } -> SegmentState.TRANSCRIBING
            parts.any { it.state == SegmentState.RECEIVED } -> SegmentState.RECEIVED
            else -> SegmentState.DONE
        }

    fun toItem(bookmarks: Int): TimelineItem {
        val receiving = state == SegmentState.RECEIVING
        val size = parts.sumOf { it.sizeBytes }
        return TimelineItem(
            id = key,
            startUnixMs = parts.first().startUnixMs,
            durationMs = durations.sum(),
            durationIsEstimate = parts.any { it.durationMs == null },
            place = place,
            preview = rows.mapNotNull { it.preview }.firstOrNull(),
            state = state,
            receiveProgress = if (receiving) (if (size > 0) parts.sumOf { it.receivedBytes }.toFloat() / size else 0f) else null,
            bookmarkCount = bookmarks,
            preserveAudio = parts.all { it.preserveAudio },
            audioDeleted = parts.any { it.audioDeleted },
            segmentCount = parts.size,
        )
    }

    /** Every part's file, or nothing when any is missing (then the entry cannot be played). */
    fun audioFiles(): List<File> {
        val files = parts.map { s ->
            s.filePath?.let(::File)?.takeIf { !s.audioDeleted && s.state != SegmentState.RECEIVING && it.exists() }
        }
        return if (files.any { it == null }) emptyList() else files.filterNotNull()
    }

    fun partStarts(): List<Pair<Long, Long?>> = parts.mapIndexed { i, p -> starts[i] to p.startUnixMs }

    /** Real time at entry offset [offsetMs]; parts were recorded with gaps, so map per part. */
    fun clockAt(offsetMs: Long): Long? {
        val i = starts.dropLast(1).indexOfLast { it <= offsetMs }.coerceAtLeast(0)
        return parts[i].startUnixMs?.plus(offsetMs - starts[i])
    }

    private fun startOf(segmentRowId: Long): Long = starts[parts.indexOfFirst { it.rowId == segmentRowId }.coerceAtLeast(0)]

    fun offsetOfSentence(s: SentenceEntity): Long = startOf(s.segmentRowId) + s.startMs

    fun sentence(s: SentenceEntity): Sentence {
        val base = startOf(s.segmentRowId)
        return Sentence(s.idx, base + s.startMs, base + s.endMs, s.text)
    }

    /** Sentences of all parts on the entry timeline, renumbered 0..n−1 in playing order. */
    fun sentences(all: List<SentenceEntity>): List<Sentence> {
        val order = parts.withIndex().associate { (i, p) -> p.rowId to i }
        return all.sortedWith(compareBy({ order[it.segmentRowId] ?: 0 }, { it.idx }))
            .mapIndexed { i, s -> sentence(s).copy(index = i) }
    }

    fun offsetOf(bookmark: EventEntity): Long {
        val same = parts.filter { it.bootCount == bookmark.bootCount && it.startUptimeMs != null }
        if (same.isEmpty()) return 0
        // Parts of an entry share one boot, so their uptimes line up with the continuous timeline.
        return EntryGrouping.offsetInEntry(parts.mapIndexed { i, p -> (p.startUptimeMs ?: 0) to durations[i] }, bookmark.uptimeMs)
    }
}

/** Before the whole segment is here: 16 kbps Opus is 2 bytes/ms plus a 2-byte length per frame. */
internal fun estimateDurationMs(s: SegmentEntity): Long {
    val frameMs = (s.frameMs ?: 20).coerceAtLeast(1)
    val payload = (s.sizeBytes - 32).coerceAtLeast(0)
    return (payload / (2.0 + 2.0 / frameMs)).toLong()
}
