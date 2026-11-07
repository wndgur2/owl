package dev.owl.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.owl.app.AppContainer
import dev.owl.app.R
import dev.owl.app.ui.components.PendantIcon
import dev.owl.app.ui.components.owlViewModel
import dev.owl.app.ui.device.DeviceScreen
import dev.owl.app.ui.device.DeviceViewModel
import dev.owl.app.ui.search.SearchScreen
import dev.owl.app.ui.search.SearchViewModel
import dev.owl.app.ui.timeline.TimelineScreen
import dev.owl.app.ui.stats.StatsScreen
import dev.owl.app.ui.stats.StatsViewModel
import dev.owl.app.ui.timeline.TimelineViewModel
import dev.owl.app.usage.UsageSessions
import kotlinx.coroutines.delay

enum class MainTab { TIMELINE, SEARCH, STATS, DEVICE }

/**
 * The bottom-navigation screen (타임라인 / 검색 / 집계 / 기기). Tabs are plain state rather than nested
 * destinations; each tab's scroll position etc. is kept in a [rememberSaveableStateHolder].
 * The ViewModels are scoped to the "main" back-stack entry, so they survive tab switches.
 */
@Composable
fun MainScreen(
    container: AppContainer,
    onOpenEntry: (id: Long, atMs: Long?) -> Unit,
    onOpenSettings: () -> Unit,
    onRegister: () -> Unit,
    onReRegister: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.TIMELINE) }
    var openPlaceMenu by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()

    // While this screen is up, app use shows without waiting for the periodic read (A18). The app
    // just left counts as finished only once the same-app merge window has passed, so read again then.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            container.appUsage.sync()
            delay(UsageSessions.MERGE_GAP_MS + 500)
            container.appUsage.sync()
        }
    }

    val timelineVm = owlViewModel { TimelineViewModel(container.timelineRepository, container.appUsage) }
    val searchVm = owlViewModel { SearchViewModel(container.timelineRepository) }
    // Back on any other tab goes to 타임라인 first (Android's convention for bottom tabs), then exits.
    BackHandler(enabled = tab != MainTab.TIMELINE) { tab = MainTab.TIMELINE }

    val statsVm = owlViewModel { StatsViewModel(container.appUsage, container.placesRepository) }
    val deviceVm = owlViewModel { DeviceViewModel(container.deviceController, container.deviceRegistrar) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.TIMELINE,
                    onClick = { tab = MainTab.TIMELINE },
                    icon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null) },
                    label = { Text(stringResource(R.string.tab_timeline)) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.SEARCH,
                    onClick = { tab = MainTab.SEARCH },
                    icon = { Icon(Icons.Outlined.Search, null) },
                    label = { Text(stringResource(R.string.tab_search)) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.STATS,
                    onClick = { tab = MainTab.STATS },
                    icon = { Icon(Icons.Outlined.BarChart, null) },
                    label = { Text(stringResource(R.string.tab_stats)) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.DEVICE,
                    onClick = { tab = MainTab.DEVICE },
                    icon = { PendantIcon() },
                    label = { Text(stringResource(R.string.tab_device)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.TIMELINE -> TimelineScreen(
                        viewModel = timelineVm,
                        onOpenEntry = { onOpenEntry(it, null) },
                        onOpenPlaces = {
                            openPlaceMenu = true
                            tab = MainTab.SEARCH
                        },
                    )
                    MainTab.SEARCH -> SearchScreen(
                        viewModel = searchVm,
                        openPlaceMenu = openPlaceMenu,
                        onPlaceMenuOpened = { openPlaceMenu = false },
                        onOpenEntry = onOpenEntry,
                    )
                    MainTab.STATS -> StatsScreen(statsVm)
                    MainTab.DEVICE -> DeviceScreen(deviceVm, onOpenSettings = onOpenSettings, onRegister = onRegister, onReRegister = onReRegister)
                }
            }
        }
    }
}
