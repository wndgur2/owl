package dev.owl.app.usage

import dev.owl.app.data.db.AppUsageDao
import dev.owl.app.data.db.AppUsageEntity
import dev.owl.app.usage.UsageEvent.Type.PAUSED
import dev.owl.app.usage.UsageEvent.Type.RESUMED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppUsageRecorderTest {
    private class FakeDao : AppUsageDao {
        val rows = MutableStateFlow<List<AppUsageEntity>>(emptyList())

        override suspend fun insertAll(sessions: List<AppUsageEntity>) {
            val seen = rows.value.map { it.packageName to it.startMs }.toSet()
            rows.value += sessions.filter { (it.packageName to it.startMs) !in seen }
        }

        override fun overlapping(from: Long, to: Long): Flow<List<AppUsageEntity>> =
            rows.map { all -> all.filter { it.startMs < to && it.endMs > from } }

        override fun endingAfter(sinceMs: Long): Flow<List<AppUsageEntity>> =
            rows.map { all -> all.filter { it.endMs > sinceMs } }

        override suspend fun all() = rows.value
        override fun hasRecords(): Flow<Boolean> = rows.map { it.isNotEmpty() }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    private class FakeState : AppUsageStateStore {
        val on = MutableStateFlow(false)
        var cursor: Long? = null
        var clock: ClockReading? = null
        override val appUsageEnabled: Flow<Boolean> = on
        override suspend fun setAppUsageEnabled(enabled: Boolean) {
            on.value = enabled
            if (!enabled) {
                cursor = null
                clock = null
            }
        }
        override suspend fun appUsageCursor() = cursor
        override suspend fun appUsageClock() = clock
        override suspend fun setAppUsageCursor(ms: Long, clock: ClockReading) {
            cursor = ms
            this.clock = clock
        }
    }

    private class FakeSource : UsageEventSource {
        var access = true
        var locked = false
        val events = mutableListOf<UsageEvent>()
        val queries = mutableListOf<Long>()
        val hidden = mutableSetOf<String>()
        var reading = ClockReading(bootCount = 1, wallMinusElapsedMs = 0)
        override fun clock() = reading
        override fun hasAccess() = access
        override fun events(fromMs: Long, toMs: Long): List<UsageEvent>? {
            if (locked) return null
            queries += fromMs
            return events.filter { it.timeMs in fromMs until toMs }
        }
        override fun labels(packages: Set<String>) = (packages - hidden).associateWith { "$it 앱" }
    }

    private val dao = FakeDao()
    private val state = FakeState()
    private val source = FakeSource()
    private var now = 1_000_000L
    private val recorder = AppUsageRecorder(dao, state, source, clock = { now })

    private fun used(pkg: String, from: Long, to: Long) {
        source.events += UsageEvent(pkg, RESUMED, from)
        source.events += UsageEvent(pkg, PAUSED, to)
    }

    @Test
    fun recordsNothingWhileOff() = runTest {
        used("a", 1_000, 2_000)
        recorder.sync()
        assertEquals(emptyList<Long>(), source.queries)
    }

    @Test
    fun startsFromTheMomentItIsTurnedOn() = runTest {
        used("before", 900_000, 950_000)
        recorder.setEnabled(true)
        used("after", 1_000_000, 1_030_000)
        now = 1_100_000
        recorder.sync()
        assertEquals(listOf("after"), dao.rows.value.map { it.packageName })
        assertEquals(1_100_000L, state.cursor)
    }

    @Test
    fun readingAgainDoesNotDuplicate() = runTest {
        recorder.setEnabled(true)
        used("a", 1_000_000, 1_030_000)
        now = 1_100_000
        recorder.sync()
        state.cursor = 1_000_000
        now = 1_200_000
        recorder.sync()
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun runningSessionIsStoredOnceItEndsInALaterRead() = runTest {
        recorder.setEnabled(true)
        source.events += UsageEvent("a", RESUMED, 1_010_000)
        now = 1_100_000
        recorder.sync()
        assertEquals(0, dao.rows.value.size)
        assertEquals(1_010_000L, state.cursor)

        source.events += UsageEvent("a", PAUSED, 1_150_000)
        now = 1_200_000
        recorder.sync()
        assertEquals(listOf(1_010_000L to 1_150_000L), dao.rows.value.map { it.startMs to it.endMs })
    }

    @Test
    fun withoutAccessTheCursorWaitsSoLaterReadsCatchUp() = runTest {
        recorder.setEnabled(true)
        used("a", 1_000_000, 1_030_000)
        source.access = false
        now = 1_100_000
        recorder.sync()
        assertEquals(1_000_000L, state.cursor)

        source.access = true
        recorder.sync()
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun lockedSinceBootDoesNotMoveTheCursor() = runTest {
        recorder.setEnabled(true)
        source.locked = true
        now = 1_100_000
        recorder.sync()
        assertEquals(1_000_000L, state.cursor)
    }

    @Test
    fun homeScreenAndSystemAppsAreLeftOut() = runTest {
        recorder.setEnabled(true)
        used("launcher", 1_000_000, 1_030_000)
        used("a", 1_030_000, 1_060_000)
        source.hidden += "launcher"
        now = 1_100_000
        recorder.sync()
        assertEquals(listOf("a"), dao.rows.value.map { it.packageName })
    }

    @Test
    fun turningOffStoresWhatHappenedAndKeepsRecords() = runTest {
        recorder.setEnabled(true)
        used("a", 1_000_000, 1_030_000)
        now = 1_100_000
        recorder.setEnabled(false)
        assertFalse(state.on.value)
        assertEquals(null, state.cursor)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun turningOnAgainDoesNotReachBackOverTheOffPeriod() = runTest {
        recorder.setEnabled(true)
        recorder.setEnabled(false)
        used("while-off", 1_010_000, 1_040_000)
        now = 1_100_000
        recorder.setEnabled(true)
        now = 1_200_000
        recorder.sync()
        assertEquals(0, dao.rows.value.size)
    }

    @Test
    fun deleteAllDoesNotBringPendingSessionsBack() = runTest {
        recorder.setEnabled(true)
        used("a", 1_000_000, 1_030_000)
        now = 1_100_000
        recorder.sync()
        state.cursor = 1_000_000
        recorder.deleteAll()
        now = 1_200_000
        recorder.sync()
        assertEquals(0, dao.rows.value.size)
    }

    /** What the system does when the clock is set: every stored event moves with it. */
    private fun setClockForward(byMs: Long) {
        now += byMs
        source.reading = source.reading.copy(wallMinusElapsedMs = source.reading.wallMinusElapsedMs + byMs)
        val moved = source.events.map { it.copy(timeMs = it.timeMs + byMs) }
        source.events.clear()
        source.events += moved
    }

    @Test
    fun settingTheClockForwardDoesNotStoreSessionsTwiceOrReachBeforeTurningOn() = runTest {
        used("before", 900_000, 950_000)
        recorder.setEnabled(true)
        used("a", 1_000_000, 1_030_000)
        now = 1_100_000
        recorder.sync()
        assertEquals(listOf("a"), dao.rows.value.map { it.packageName })

        setClockForward(7 * 3_600_000L)
        used("b", now + 10_000, now + 40_000)
        now += 100_000
        recorder.sync()
        assertEquals(listOf("a", "b"), dao.rows.value.map { it.packageName })
    }

    @Test
    fun settingTheClockBackStillReadsWhatComesNext() = runTest {
        recorder.setEnabled(true)
        now = 1_100_000
        recorder.sync()
        setClockForward(-3_600_000L)
        used("a", now + 10_000, now + 40_000)
        now += 100_000
        recorder.sync()
        assertEquals(listOf("a"), dao.rows.value.map { it.packageName })
    }

    @Test
    fun clockShiftIgnoresDriftAndReboots() {
        val before = ClockReading(bootCount = 1, wallMinusElapsedMs = 10_000)
        assertEquals(0, UsageSessions.clockShiftMs(null, before))
        assertEquals(0, UsageSessions.clockShiftMs(before, before.copy(wallMinusElapsedMs = 11_500)))
        assertEquals(0, UsageSessions.clockShiftMs(before, ClockReading(bootCount = 2, wallMinusElapsedMs = 500_000)))
        assertEquals(5_000, UsageSessions.clockShiftMs(before, before.copy(wallMinusElapsedMs = 15_000)))
        assertEquals(-5_000, UsageSessions.clockShiftMs(before, before.copy(wallMinusElapsedMs = 5_000)))
    }

    @Test
    fun summaryAddsUpOnlyThatPeriod() = runTest {
        val start = 10_000_000L
        dao.insertAll(
            listOf(
                AppUsageEntity(packageName = "a", label = "에이", startMs = start - 1_000, endMs = start + 1_000),
                AppUsageEntity(packageName = "b", label = "비", startMs = start - 5_000, endMs = start - 4_000),
            ),
        )
        val summary = recorder.summary(start, start + 60_000).first()
        assertEquals(listOf("a"), summary.map { it.packageName })
        assertEquals(1_000L, summary.single().totalMs)
    }

    @Test
    fun cardsLeaveOutOlderOnesWhenTheTimelineHasNotLoadedThem() = runTest {
        dao.insertAll(
            listOf(
                AppUsageEntity(rowId = 1, packageName = "a", label = "에이", startMs = 1_000, endMs = 2_000),
                AppUsageEntity(rowId = 2, packageName = "b", label = "비", startMs = 1_000_000, endMs = 1_001_000),
            ),
        )
        assertEquals(listOf(1L, 2L), recorder.cards(null).first().map { it.id })
        assertEquals(listOf(2L), recorder.cards(500_000).first().map { it.id })
    }
}
