package dev.owl.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.time.ZoneId

class JsonExporterTest {
    @Test
    fun escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", JsonExporter.str("a\"b\\c\nd\u0001"))
        assertEquals("\"공학관 4층\"", JsonExporter.str("공학관 4층"))
    }

    @Test
    fun writesRecords() {
        val w = StringWriter()
        JsonExporter.write(
            w,
            listOf(
                ExportRecord("recording", 1_793_440_000_000, 60_000, "집", 37.5, 127.0, listOf(1_000), listOf(Triple(0L, 5_000L, "안녕"))),
                ExportRecord("bookmark", null, null, null, null, null, emptyList(), emptyList()),
            ),
            ZoneId.of("Asia/Seoul"),
        )
        val json = w.toString()
        assertTrue(json.contains("\"start\":\"2026-10-31T"))
        assertTrue(json.contains("+09:00\""))
        assertTrue(json.contains("\"place\":\"집\""))
        assertTrue(json.contains("{\"startMs\":0,\"endMs\":5000,\"text\":\"안녕\"}"))
        assertTrue(json.contains("{\"kind\":\"bookmark\",\"start\":null,\"durationMs\":null,\"place\":null"))
    }
}
