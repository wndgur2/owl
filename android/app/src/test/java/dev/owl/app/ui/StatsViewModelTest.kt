package dev.owl.app.ui

import dev.owl.app.model.AppUsageCard
import dev.owl.app.model.AppUsageRepository
import dev.owl.app.model.AppUsageSummary
import dev.owl.app.model.StubPlacesRepository
import dev.owl.app.ui.stats.StatsSpan
import dev.owl.app.ui.stats.StatsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** A19: the period is what is added up, and it must not go stale while the app stays alive. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val asked = mutableListOf<Pair<Long, Long>>()

    private val usage = object : AppUsageRepository {
        override val enabled: Flow<Boolean> = flowOf(true)
        override val hasRecords: Flow<Boolean> = flowOf(false)
        override fun hasAccess() = true
        override suspend fun setEnabled(on: Boolean) = Unit
        override suspend fun sync() = Unit
        override fun cards(sinceMs: Long?): Flow<List<AppUsageCard>> = flowOf(emptyList())
        override fun summary(fromMs: Long, toMs: Long): Flow<List<AppUsageSummary>> {
            asked += fromMs to toMs
            return flowOf(emptyList())
        }
        override suspend fun deleteAll() = Unit
    }

    private var today = LocalDate.of(2026, 11, 7)
    private fun viewModel() = StatsViewModel(usage, StubPlacesRepository(), { today }, { ZoneOffset.UTC })
    private fun ms(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun todayFollowsTheCalendarWhenTheTabIsShownAgain() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        val collecting = launch { vm.state.collect {} }
        today = today.plusDays(1)
        vm.onScreenShown()
        assertEquals(LocalDate.of(2026, 11, 8), vm.state.value.period.end)
        assertTrue(vm.state.value.isLatest)
        collecting.cancel()
    }

    @Test
    fun aDayPickedByHandStaysWhenTheTabIsShownAgain() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        val collecting = launch { vm.state.collect {} }
        vm.previous()
        today = today.plusDays(1)
        vm.onScreenShown()
        assertEquals(LocalDate.of(2026, 11, 6), vm.state.value.period.end)
        assertFalse(vm.state.value.isLatest)
        collecting.cancel()
    }

    @Test
    fun aWeekAddsUpSevenWholeDaysEndingOnTheChosenDay() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        val collecting = launch { vm.state.collect {} }
        vm.setSpan(StatsSpan.WEEK)
        assertEquals(ms(LocalDate.of(2026, 11, 1)) to ms(LocalDate.of(2026, 11, 8)), asked.last())
        collecting.cancel()
    }

    @Test
    fun movingGoesByTheWholeSpanAndStopsAtToday() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        val collecting = launch { vm.state.collect {} }
        vm.setSpan(StatsSpan.WEEK)
        vm.previous()
        assertEquals(LocalDate.of(2026, 10, 31), vm.state.value.period.end)
        vm.previous()
        vm.next()
        vm.next()
        vm.next()
        assertEquals(today, vm.state.value.period.end)
        assertTrue(vm.state.value.isLatest)
        collecting.cancel()
    }
}
