package dev.owl.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.owl.app.R
import dev.owl.app.model.Place
import dev.owl.app.model.RetentionPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Times are shown in the phone's zone with Korean day/month names. */
val OwlZone: ZoneId get() = ZoneId.systemDefault()

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(OwlZone).toLocalDate()

fun today(): LocalDate = LocalDate.now(OwlZone)

/**
 * All user-visible date, time and size formatting. Patterns and words come from string resources
 * so every Korean string lives in `strings_ui.xml`.
 */
class OwlFormats(private val context: Context) {
    private val locale = Locale.KOREAN
    private fun fmt(id: Int) = DateTimeFormatter.ofPattern(context.getString(id), locale)

    private val hm = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val dayLong = fmt(R.string.fmt_day_long)
    private val dayLongYear = fmt(R.string.fmt_day_long_year)
    private val monthDay = fmt(R.string.fmt_month_day)
    private val monthDayYear = fmt(R.string.fmt_month_day_year)

    fun hm(ms: Long): String = hm.format(Instant.ofEpochMilli(ms).atZone(OwlZone))
    fun hms(ms: Long): String = hms.format(Instant.ofEpochMilli(ms).atZone(OwlZone))

    /** "10월 31일 토요일" (year added when it is not this year). */
    fun dayLong(date: LocalDate): String =
        if (date.year == today().year) dayLong.format(date) else dayLongYear.format(date)

    /** "10월 31일". */
    fun monthDay(date: LocalDate): String =
        if (date.year == today().year) monthDay.format(date) else monthDayYear.format(date)

    /** "오늘 13:02" / "어제 22:41" / "10월 29일 13:02". */
    fun relativeDayTime(ms: Long): String {
        val date = ms.toLocalDate()
        val day = when (date) {
            today() -> context.getString(R.string.today)
            today().minusDays(1) -> context.getString(R.string.yesterday)
            else -> monthDay(date)
        }
        return "$day ${hm(ms)}"
    }

    /** "12분" / "약 3분" / "40초" / "1시간 25분" (merged entries can run long). */
    fun duration(ms: Long, estimate: Boolean): String {
        val minutes = (ms / 60_000.0).roundToLong()
        val text = when {
            ms < 60_000 -> context.getString(R.string.duration_seconds, (ms / 1000).coerceAtLeast(1))
            minutes < 60 -> context.getString(R.string.duration_minutes, minutes)
            minutes % 60 == 0L -> context.getString(R.string.duration_hours, minutes / 60)
            else -> context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
        }
        return if (estimate) context.getString(R.string.about_prefix, text) else text
    }

    /** Player clock "m:ss" (or "h:mm:ss" for long recordings). */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
    }

    /**
     * The user-given name, else "알 수 없는 장소" — for unnamed places and for entries without a
     * GPS fix alike. No reverse geocoding (PRD N8); coordinates only appear as detail ([coordinates]).
     */
    fun place(place: Place?): String = place?.name ?: context.getString(R.string.place_unknown)

    /** "37.5665, 126.9780" (~10 m precision). */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(locale, "%.4f, %.4f", latitude, longitude)

    fun megabytes(mb: Double): String =
        if (mb >= 10 || mb == mb.roundToInt().toDouble()) mb.roundToInt().toString() else String.format(locale, "%.1f", mb)

    /** "412 MB" / "1.2 GB" / "830 KB". */
    fun bytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> context.getString(R.string.size_gb, String.format(locale, "%.1f", gb))
            mb >= 1 -> context.getString(R.string.size_mb, megabytes(mb))
            else -> context.getString(R.string.size_kb, kb.roundToInt().coerceAtLeast(if (bytes > 0) 1 else 0))
        }
    }

    fun retention(policy: RetentionPolicy): String = context.getString(
        when (policy) {
            RetentionPolicy.DAYS_7 -> R.string.retention_summary_7
            RetentionPolicy.DAYS_30 -> R.string.retention_summary_30
            RetentionPolicy.DAYS_90 -> R.string.retention_summary_90
            RetentionPolicy.NEVER -> R.string.retention_never
            RetentionPolicy.AFTER_TRANSCRIPTION -> R.string.retention_after_stt
        },
    )
}

@Composable
fun rememberOwlFormats(): OwlFormats {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { OwlFormats(context) }
}
