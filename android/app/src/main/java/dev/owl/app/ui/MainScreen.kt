package dev.owl.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.owl.app.AppContainer
import dev.owl.app.R
import dev.owl.app.ui.components.PendantIcon
import dev.owl.app.ui.components.owlViewModel
import dev.owl.app.ui.device.DeviceScreen
import dev.owl.app.ui.device.DeviceViewModel
import dev.owl.app.ui.search.SearchScreen
import dev.owl.app.ui.search.SearchViewModel
import dev.owl.app.ui.timeline.TimelineScreen
import dev.owl.app.ui.timeline.TimelineViewModel

enum class MainTab { TIMELINE, SEARCH, DEVICE }

/**
 * The bottom-navigation screen (타임라인 / 검색 / 기기). Tabs are plain state rather than nested
 * destinations; each tab's scroll position etc. is kept in a [rememberSaveableStateHolder].
 * The three ViewModels are scoped to the "main" back-stack entry, so they survive tab switches.
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

    val timelineVm = owlViewModel { TimelineViewModel(container.timelineRepository) }
    val searchVm = owlViewModel { SearchViewModel(container.timelineRepository) }
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
                    MainTab.DEVICE -> DeviceScreen(deviceVm, onOpenSettings = onOpenSettings, onRegister = onRegister, onReRegister = onReRegister)
                }
            }
        }
    }
}
