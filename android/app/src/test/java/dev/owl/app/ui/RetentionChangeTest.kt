package dev.owl.app.ui

import dev.owl.app.model.RetentionController
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.StubTimelineRepository
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.ThemeMode
import dev.owl.app.ui.settings.PendingRetention
import dev.owl.app.ui.settings.RetentionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** decisions #48: a new retention period applies at once; asks first when that deletes audio. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetentionChangeTest {
    private val current = MutableStateFlow(RetentionPolicy.DAYS_30)

    private val settings = object : SettingsRepository {
        override val retention: Flow<RetentionPolicy> = current
        override suspend fun setRetention(policy: RetentionPolicy) { current.value = policy }
        override val locationEnabled: Flow<Boolean> = flowOf(true)
        override suspend fun setLocationEnabled(enabled: Boolean) = Unit
        override val onboardingDone: Flow<Boolean> = flowOf(true)
        override suspend fun setOnboardingDone(done: Boolean) = Unit
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.DARK)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
    }

    private val changed = mutableListOf<RetentionPolicy>()
    private val controller = object : RetentionController {
        override suspend fun deletableNow(policy: RetentionPolicy) = if (policy == RetentionPolicy.DAYS_7) 3 else 0
        override suspend fun change(policy: RetentionPolicy) {
            changed += policy
            settings.setRetention(policy)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun longerPeriodAppliesWithoutAsking() = runTest {
        val vm = RetentionViewModel(settings, StubTimelineRepository(), controller)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.setPolicy(RetentionPolicy.DAYS_90)
        assertEquals(listOf(RetentionPolicy.DAYS_90), changed)
        assertNull(vm.state.value.pending)
    }

    @Test
    fun shorterPeriodThatDeletesAsksFirst() = runTest {
        val vm = RetentionViewModel(settings, StubTimelineRepository(), controller)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.setPolicy(RetentionPolicy.DAYS_7)
        assertEquals(PendingRetention(RetentionPolicy.DAYS_7, 3), vm.state.value.pending)
        assertEquals(emptyList<RetentionPolicy>(), changed)
        vm.cancel()
        assertEquals(RetentionPolicy.DAYS_30, vm.state.value.policy)
        vm.setPolicy(RetentionPolicy.DAYS_7)
        vm.confirm()
        assertEquals(listOf(RetentionPolicy.DAYS_7), changed)
        assertEquals(RetentionPolicy.DAYS_7, vm.state.value.policy)
    }
}
