package dev.owl.app.export

import android.content.ContentResolver
import android.net.Uri
import dev.owl.app.data.PlaceResolver
import dev.owl.app.data.assemble
import dev.owl.app.data.db.OwlDatabase
import kotlinx.coroutines.flow.first
import dev.owl.app.model.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A13: writes every recording's text, time and place to a file the user picked (SAF).
 * This is the only way data leaves the phone (PRD N8), and it is the user's own action.
 * Audio is not included. JSON so it can be re-imported later (decisions.md #22: moving phones).
 */
class JsonExporter(
    private val db: OwlDatabase,
    private val resolver: ContentResolver,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Exporter {
    override suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val records = collect()
            val out = resolver.openOutputStream(uri, "wt") ?: error("cannot open $uri")
            out.bufferedWriter(Charsets.UTF_8).use { write(it, records, zone) }
            records.size
        }
    }

    /** One record per timeline entry (merged segments), plus bookmarks made outside recordings. */
    suspend fun collect(): List<ExportRecord> {
        val entries = assemble(db.segments().allAudioRows().first()).map { e ->
            val sentences = e.sentences(e.parts.flatMap { db.sentences().forSegment(it.rowId) })
            ExportRecord(
                kind = "recording", start = e.parts.first().startUnixMs, durationMs = e.toItem(0).durationMs,
                placeName = e.place?.name, latitude = e.place?.latitude, longitude = e.place?.longitude,
                bookmarksMs = db.events().entryBookmarksOnce(e.key).map { e.offsetOf(it) },
                sentences = sentences.map { Triple(it.startMs, it.endMs, it.text) },
                sortTimeMs = e.sortTimeMs,
            )
        }
        val places = db.places().all()
        val bookmarks = db.events().standaloneBookmarks(Long.MIN_VALUE).first().map { b ->
            // Same place the timeline shows for it: the nearest fix in time, named if inside a named place.
            val fix = b.timeMs?.let { t -> db.locations().nearest(t, t - PlaceResolver.MAX_GAP_MS, t + PlaceResolver.MAX_GAP_MS) }
            ExportRecord(
                kind = "bookmark", start = b.timeMs, durationMs = 0,
                placeName = fix?.let { PlaceResolver.nearestPlace(places, it.latitude, it.longitude)?.name },
                latitude = fix?.latitude, longitude = fix?.longitude,
                bookmarksMs = listOf(0), sentences = emptyList(), sortTimeMs = b.sortTimeMs,
            )
        }
        return (entries + bookmarks).sortedBy { it.sortTimeMs }
    }

    companion object {
        fun write(w: Writer, records: List<ExportRecord>, zone: ZoneId) {
            val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            w.write("{\"format\":\"owl-export\",\"version\":1,\"exportedAt\":${str(fmt.format(Instant.now().atZone(zone)))},\"records\":[")
            records.forEachIndexed { i, r ->
                if (i > 0) w.write(",")
                w.write("\n{\"kind\":${str(r.kind)},\"start\":${r.start?.let { str(fmt.format(Instant.ofEpochMilli(it).atZone(zone))) } ?: "null"}")
                w.write(",\"durationMs\":${r.durationMs ?: "null"}")
                w.write(",\"place\":${r.placeName?.let(::str) ?: "null"}")
                w.write(",\"latitude\":${r.latitude ?: "null"},\"longitude\":${r.longitude ?: "null"}")
                w.write(",\"bookmarksMs\":[${r.bookmarksMs.joinToString(",")}]")
                w.write(",\"sentences\":[")
                r.sentences.forEachIndexed { j, (a, b, text) ->
                    if (j > 0) w.write(",")
                    w.write("{\"startMs\":$a,\"endMs\":$b,\"text\":${str(text)}}")
                }
                w.write("]}")
            }
            w.write("\n]}\n")
        }

        /** JSON string literal. */
        fun str(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}

data class ExportRecord(
    /** "recording" (a timeline entry) or "bookmark" (pressed outside any recording). */
    val kind: String,
    val start: Long?,
    val durationMs: Long?,
    val placeName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val bookmarksMs: List<Long>,
    val sentences: List<Triple<Long, Long, String>>,
    val sortTimeMs: Long = 0,
)
