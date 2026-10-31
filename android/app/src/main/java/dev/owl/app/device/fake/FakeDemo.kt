package dev.owl.app.device.fake

import android.util.Log
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.data.db.PlaceEntity
import dev.owl.app.location.LocationSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Debug-build helper around the fake pendant: fills it with two days of history the first time
 * (including one boot that was never time-synced → "시각 알 수 없음"), keeps recording new
 * segments while the app runs, and executes commands sent with adb (see android/README.md).
 */
class FakeDemo(
    private val db: OwlDatabase,
    private val locations: LocationSink,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class DemoPlace(val name: String, val lat: Double, val lon: Double)

    private val library = DemoPlace("중앙도서관", 37.5665, 126.9780)
    private val home = DemoPlace("집", 37.5796, 126.9910)
    private val engineering = DemoPlace("공학관 4층", 37.5610, 126.9700)
    private val cafe = DemoPlace("학생회관 카페", 37.5640, 126.9830)
    private val random = Random(42)

    /** Creates the fake pendant. [firstRun] seeds history; otherwise it starts empty, ids continuing. */
    suspend fun createDevice(firstRun: Boolean, nextId: Long): FakeOwlDevice = withContext(Dispatchers.Default) {
        val now = clock()
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = { h: Int, m: Int ->
            LocalDate.now().minusDays(1).atTime(LocalTime.of(h, m)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val dev = FakeOwlDevice(
            scope, OpusToneSource(), clock, chunkIntervalMs = 3, initialBootCount = 2,
            bootAgoMs = now - yesterday(8, 0) + 3 * HOUR, initialNextId = nextId,
        )
        dev.dropProbability = 0.005 // a slightly flaky radio exercises gap recovery
        if (!firstRun) {
            dev.reboot(agoMs = now - yesterday(8, 0))
            return@withContext dev
        }
        places().forEach { db.places().insert(PlaceEntity(0, it.name, it.lat, it.lon)) }
        // Boot 2: never synced, so this one keeps an unknown time.
        dev.recordAudio(durationMs = 40_000, agoMs = now - yesterday(7, 0))
        dev.reboot(agoMs = now - yesterday(8, 0))
        // Boot 3 (current): yesterday evening and today.
        val plan = mutableListOf(
            Triple(yesterday(19, 15), 150_000L, library),
            Triple(yesterday(19, 42), 60_000L, library),
            Triple(yesterday(22, 41), 120_000L, home),
        )
        val todayPlan = listOf(
            Triple(now - 4 * HOUR, 180_000L, engineering),
            Triple(now - 3 * HOUR - 13 * MIN, 75_000L, engineering),
            Triple(now - 75 * MIN, 140_000L, cafe),
            Triple(now - 50 * MIN, 50_000L, cafe),
        )
        plan += todayPlan.filter { it.first >= dayStart }
        for ((start, dur, place) in plan) {
            dev.recordAudio(durationMs = dur, agoMs = now - start)
            recordFixes(place, start, dur)
        }
        dev.bookmark(agoMs = now - (todayPlan[1].first + 20_000))
        dev.bookmark(agoMs = now - (plan[0].first + 63_000))
        dev
    }

    private fun places() = listOf(library, home, engineering, cafe)

    private suspend fun recordFixes(place: DemoPlace, start: Long, durationMs: Long) {
        var t = start - 2 * MIN
        while (t <= start + durationMs + 2 * MIN) {
            locations.record(t, place.lat + random.nextDouble(-2e-4, 2e-4), place.lon + random.nextDouble(-2e-4, 2e-4), 15f)
            t += 5 * MIN
        }
    }

    /** While the process lives, "talk" every few minutes like a pendant would. */
    fun startRecordingLoop(device: FakeOwlDevice, everyMs: Long = 4 * MIN) {
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(everyMs)
                if (!device.recordingOn) continue
                val dur = 30_000L + random.nextLong(60_000)
                device.recordAudio(durationMs = dur, agoMs = dur)
                recordFixes(cafe, clock() - dur, dur)
                Log.i("OwlFake", "recorded a ${dur / 1000}s segment")
            }
        }
    }

    /** adb commands: record, bookmark, disconnect, drop, fail-connect, reboot, button-rec. */
    suspend fun command(device: FakeOwlDevice, cmd: String, arg: Long?): String = withContext(Dispatchers.Default) {
        when (cmd) {
            "record" -> "recorded id ${device.recordAudio(durationMs = (arg ?: 60) * 1000, agoMs = (arg ?: 60) * 1000).also { recordFixes(cafe, clock() - (arg ?: 60) * 1000, (arg ?: 60) * 1000) }}"
            "bookmark" -> { device.bookmark(); "bookmark" }
            "disconnect" -> { device.disconnectAfterChunks = (arg ?: 40).toInt(); "link drops after ${device.disconnectAfterChunks} chunks of the next FETCH" }
            "drop" -> { device.dropChunkIndices += (arg ?: 5).toInt(); "drop chunk ${arg ?: 5} of the next FETCH" }
            "fail-connect" -> { device.failConnects = (arg ?: 3).toInt(); "next ${device.failConnects} connects fail" }
            "reboot" -> { device.reboot(); "rebooted, boot ${device.bootCount}" }
            "button-rec" -> { device.setRecording(!device.recordingOn); "recording ${device.recordingOn}" }
            "battery" -> { device.batteryMv = (arg ?: 3700).toInt(); "battery ${device.batteryMv} mV" }
            else -> "unknown command $cmd"
        }
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
