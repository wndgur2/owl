package dev.owl.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.owl.app.AppContainer
import dev.owl.app.ui.components.owlViewModel
import dev.owl.app.ui.entry.EntryScreen
import dev.owl.app.ui.entry.EntryViewModel
import dev.owl.app.ui.onboarding.OnboardingScreen
import dev.owl.app.ui.onboarding.OnboardingViewModel
import dev.owl.app.ui.settings.GuideScreen
import dev.owl.app.ui.settings.PrivacyScreen
import dev.owl.app.ui.settings.RetentionScreen
import dev.owl.app.ui.settings.RetentionViewModel
import dev.owl.app.ui.settings.SettingsScreen
import dev.owl.app.ui.settings.SettingsViewModel
import dev.owl.app.ui.splash.SplashScreen
import dev.owl.app.ui.splash.SplashViewModel
import dev.owl.app.ui.splash.StartDestination
import dev.owl.app.ui.theme.OwlTheme

private object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val ENTRY = "entry/{id}?at={at}"
    const val SETTINGS = "settings"
    const val RETENTION = "retention"
    const val PRIVACY = "privacy"
    const val GUIDE = "guide"

    fun entry(id: Long, atMs: Long?) = if (atMs != null) "entry/$id?at=$atMs" else "entry/$id"
}

/** Clears the whole back stack and goes to [route] (start-up, onboarding finished, unregistered). */
private fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

/** App root: theme + navigation graph. Every screen gets its dependencies from [container]. */
@Composable
fun OwlRoot(container: AppContainer) {
    OwlTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = Routes.SPLASH) {
                composable(Routes.SPLASH) {
                    SplashScreen(owlViewModel { SplashViewModel(container.settingsRepository, container.deviceRegistrar) }) { dest ->
                        nav.resetTo(if (dest == StartDestination.MAIN) Routes.MAIN else Routes.ONBOARDING)
                    }
                }
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        owlViewModel {
                            OnboardingViewModel(container.deviceRegistrar, container.settingsRepository, container.deviceController)
                        },
                        onDone = { nav.resetTo(Routes.MAIN) },
                    )
                }
                composable(Routes.MAIN) {
                    MainScreen(
                        container = container,
                        onOpenEntry = { id, at -> nav.navigate(Routes.entry(id, at)) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onRegister = { nav.navigate(Routes.ONBOARDING) },
                    )
                }
                composable(
                    Routes.ENTRY,
                    arguments = listOf(
                        navArgument("id") { type = NavType.LongType },
                        navArgument("at") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    val at = entry.arguments?.getLong("at")?.takeIf { it >= 0 }
                    EntryScreen(
                        owlViewModel { EntryViewModel(id, at, container.timelineRepository, container.newAudioPlayer()) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        owlViewModel {
                            SettingsViewModel(
                                container.settingsRepository, container.deviceRegistrar, container.deviceController, container.exporter,
                            )
                        },
                        onBack = { nav.popBackStack() },
                        onOpenRetention = { nav.navigate(Routes.RETENTION) },
                        onOpenPrivacy = { nav.navigate(Routes.PRIVACY) },
                        onOpenGuide = { nav.navigate(Routes.GUIDE) },
                        onUnregistered = { nav.resetTo(Routes.ONBOARDING) },
                    )
                }
                composable(Routes.RETENTION) {
                    RetentionScreen(
                        owlViewModel { RetentionViewModel(container.settingsRepository, container.timelineRepository) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.GUIDE) { GuideScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
