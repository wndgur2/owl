package dev.owl.app.export

import android.content.ContentResolver
import android.net.Uri
import dev.owl.app.data.db.OwlDatabase
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

    suspend fun collect(): List<ExportRecord> {
        val bookmarks = db.events().allBookmarks().groupBy { it.deviceAddress to it.bootCount }
        return db.segments().allAudio().map { s ->
            val marks = if (s.bootCount != null && s.startUptimeMs != null) {
                bookmarks[s.deviceAddress to s.bootCount].orEmpty()
                    .filter { it.uptimeMs in s.startUptimeMs..(s.startUptimeMs + (s.durationMs ?: 0)) }
                    .map { it.uptimeMs - s.startUptimeMs }
            } else {
                emptyList()
            }
            ExportRecord(
                start = s.startUnixMs, durationMs = s.durationMs, placeName = s.placeName,
                latitude = s.latitude, longitude = s.longitude, bookmarksMs = marks,
                sentences = db.sentences().forSegment(s.rowId).map { Triple(it.startMs, it.endMs, it.text) },
            )
        }
    }

    companion object {
        fun write(w: Writer, records: List<ExportRecord>, zone: ZoneId) {
            val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            w.write("{\"format\":\"owl-export\",\"version\":1,\"exportedAt\":${str(fmt.format(Instant.now().atZone(zone)))},\"records\":[")
            records.forEachIndexed { i, r ->
                if (i > 0) w.write(",")
                w.write("\n{\"start\":${r.start?.let { str(fmt.format(Instant.ofEpochMilli(it).atZone(zone))) } ?: "null"}")
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
    val start: Long?,
    val durationMs: Long?,
    val placeName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val bookmarksMs: List<Long>,
    val sentences: List<Triple<Long, Long, String>>,
)
