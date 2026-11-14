package dev.owl.app.ui

import dev.owl.app.model.StubSettingsRepository
import dev.owl.app.ui.splash.SplashViewModel
import dev.owl.app.ui.splash.StartDestination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** decisions #55: onboarding shows until it was left once; a registered pendant is not required. */
class SplashDestinationTest {
    @Test
    fun firstRunShowsOnboarding() = runTest {
        assertEquals(StartDestination.ONBOARDING, SplashViewModel(StubSettingsRepository()).destination())
    }

    @Test
    fun opensMainWithoutPendantOnceOnboardingWasLeft() = runTest {
        val settings = StubSettingsRepository().apply { setOnboardingDone(true) }
        assertEquals(StartDestination.MAIN, SplashViewModel(settings).destination())
    }
}
