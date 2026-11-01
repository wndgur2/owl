package dev.owl.app.ui

import android.content.Intent
import android.content.IntentSender
import dev.owl.app.model.DeviceController
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.DeviceUiState
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SettingsRepository
import dev.owl.app.model.ThemeMode
import dev.owl.app.ui.device.DeviceViewModel
import dev.owl.app.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Registration flow decisions #47: the refusal notice stays, "다시 등록" unregisters with progress. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterFlowTest {
    private class Registrar : DeviceRegistrar {
        val name = MutableStateFlow<String?>(null)
        var nextResult: Result<String> = Result.failure(IllegalStateException("껐다 켠 뒤 2분 안에"))
        var unregisterGate = CompletableDeferred<Unit>()
        var unregistered = 0
        override val registeredName: Flow<String?> = name
        override suspend fun startAssociation(): Result<IntentSender?> = Result.success(null)
        override suspend fun onAssociationResult(data: Intent?): Result<String> = nextResult
        override val fakeDeviceAvailable = false
        override suspend fun registerFakeDevice(): Result<String> = nextResult
        override suspend fun unregister() {
            unregisterGate.await()
            unregistered++
            name.value = null
        }
    }

    private object Controller : DeviceController {
        override val state: StateFlow<DeviceUiState> = MutableStateFlow(DeviceUiState())
        override fun syncNow() = Unit
        override fun reconnect() = Unit
        override suspend fun setRecording(on: Boolean): Result<Unit> = Result.success(Unit)
    }

    private object Settings : SettingsRepository {
        override val retention: Flow<RetentionPolicy> = flowOf(RetentionPolicy.DAYS_30)
        override suspend fun setRetention(policy: RetentionPolicy) = Unit
        override val locationEnabled: Flow<Boolean> = flowOf(true)
        override suspend fun setLocationEnabled(enabled: Boolean) = Unit
        override val onboardingDone: Flow<Boolean> = flowOf(false)
        override suspend fun setOnboardingDone(done: Boolean) = Unit
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.DARK)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun refusalStaysInTheCardUntilTheNextAttempt() = runTest {
        val registrar = Registrar()
        val vm = OnboardingViewModel(registrar, Settings, Controller)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onAssociationResult(null)
        assertEquals("껐다 켠 뒤 2분 안에", vm.state.value.registerError)
        vm.startAssociation()
        assertNull(vm.state.value.registerError)
    }

    @Test
    fun reRegisterShowsProgressThenOpensRegistration() = runTest {
        val registrar = Registrar()
        val vm = DeviceViewModel(Controller, registrar)
        var opened = false
        vm.reRegister { opened = true }
        assertTrue(vm.reRegisteringState.value)
        vm.reRegister { error("second tap must be ignored") }
        registrar.unregisterGate.complete(Unit)
        assertFalse(vm.reRegisteringState.value)
        assertTrue(opened)
        assertEquals(1, registrar.unregistered)
    }
}
