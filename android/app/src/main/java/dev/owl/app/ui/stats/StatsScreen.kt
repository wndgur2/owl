package dev.owl.app.ui.stats

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.AppUsageSummary
import dev.owl.app.model.Place
import dev.owl.app.model.PlaceSummary
import dev.owl.app.ui.components.EmptyState
import dev.owl.app.ui.components.HintCard
import dev.owl.app.ui.components.OwlDivider
import dev.owl.app.ui.components.OwlFormats
import dev.owl.app.ui.components.OwlListRow
import dev.owl.app.ui.components.OwlPermissions
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.theme.OwlTheme
import java.time.LocalDate

@Composable
fun StatsScreen(viewModel: StatsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Access is granted on a system page, so read it again whenever we come back.
    var resumes by remember { mutableIntStateOf(0) }
    var enableOnReturn by rememberSaveable { mutableStateOf(false) }
    var askingAccess by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumes++
        if (enableOnReturn) {
            enableOnReturn = false
            if (viewModel.hasUsageAccess()) viewModel.setAppUsageEnabled(true)
        }
        viewModel.onScreenShown()
    }
    val hasAccess = remember(resumes) { viewModel.hasUsageAccess() }

    StatsContent(
        state = state,
        hasAccess = hasAccess,
        onSpan = viewModel::setSpan,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onToggleAppUsage = { on ->
            when {
                !on -> viewModel.setAppUsageEnabled(false)
                hasAccess -> viewModel.setAppUsageEnabled(true)
                else -> askingAccess = true
            }
        },
        onAskAccess = { askingAccess = true },
        onDeleteAppUsage = viewModel::deleteAppUsage,
    )

    if (askingAccess) {
        AlertDialog(
            onDismissRequest = { askingAccess = false },
            title = { Text(stringResource(R.string.app_usage_access_title)) },
            text = { Text(stringResource(R.string.app_usage_access_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingAccess = false
                        try {
                            context.startActivity(OwlPermissions.usageAccessIntent())
                            enableOnReturn = !state.appUsageEnabled
                        } catch (_: ActivityNotFoundException) {
                            // No such settings page on this phone; the switch simply stays off.
                        }
                    },
                ) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { askingAccess = false }) { Text(stringResource(R.string.action_later)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(
    state: StatsUiState,
    hasAccess: Boolean,
    onSpan: (StatsSpan) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleAppUsage: (Boolean) -> Unit,
    onAskAccess: () -> Unit,
    onDeleteAppUsage: () -> Unit,
) {
    val formats = rememberOwlFormats()
    var showApps by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tab_stats),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (showApps && state.hasAppUsage) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.app_usage_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SecondaryTabRow(selectedTabIndex = if (showApps) 1 else 0, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = !showApps, onClick = { showApps = false }, text = { Text(stringResource(R.string.places_title)) })
                Tab(selected = showApps, onClick = { showApps = true }, text = { Text(stringResource(R.string.stats_tab_app_usage)) })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (showApps) appUsageSwitch(state, hasAccess, onToggleAppUsage, onAskAccess)
                item(key = "period") { PeriodControls(state, formats, onSpan, onPrevious, onNext) }
                if (showApps) appRows(state, formats) else placeRows(state, formats)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.app_usage_delete_title)) },
            text = { Text(stringResource(R.string.app_usage_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteAppUsage()
                    },
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PeriodControls(
    state: StatsUiState,
    formats: OwlFormats,
    onSpan: (StatsSpan) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val period = state.period
    Column {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsSpan.entries.forEach { span ->
                FilterChip(
                    selected = period.span == span,
                    onClick = { onSpan(span) },
                    label = {
                        Text(
                            stringResource(
                                when (span) {
                                    StatsSpan.DAY -> R.string.stats_span_day
                                    StatsSpan.WEEK -> R.string.stats_span_week
                                    StatsSpan.MONTH -> R.string.stats_span_month
                                },
                            ),
                        )
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.stats_previous))
            }
            Text(
                if (period.span == StatsSpan.DAY) {
                    formats.dayLong(period.end)
                } else {
                    stringResource(R.string.time_range, formats.monthDay(period.start), formats.monthDay(period.end))
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onNext, enabled = !state.isLatest) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.stats_next))
            }
        }
    }
}

@Composable
private fun TotalLine(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DurationLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun LazyListScope.placeRows(state: StatsUiState, formats: OwlFormats) {
    val places = state.places ?: return
    if (places.isEmpty()) {
        item(key = "places-empty") {
            EmptyState(icon = Icons.Outlined.Place, title = stringResource(R.string.stats_places_empty), body = null)
        }
    } else {
        item(key = "places-total") {
            TotalLine(stringResource(R.string.app_usage_total, formats.duration(places.sumOf { it.totalMs }, estimate = false)))
        }
        items(places, key = { "place-${it.place.name}" }) { row ->
            OwlListRow(
                title = formats.place(row.place),
                subtitle = stringResource(R.string.stats_place_visits, row.visits),
                icon = Icons.Outlined.Place,
                trailing = { DurationLabel(formats.duration(row.totalMs, estimate = false)) },
            )
        }
    }
    item(key = "places-hint") {
        HintCard(stringResource(R.string.stats_places_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    }
}

private fun LazyListScope.appUsageSwitch(
    state: StatsUiState,
    hasAccess: Boolean,
    onToggle: (Boolean) -> Unit,
    onAskAccess: () -> Unit,
) {
    item(key = "switch") {
        OwlListRow(
            title = stringResource(R.string.app_usage_title),
            subtitle = stringResource(if (state.appUsageEnabled) R.string.app_usage_on else R.string.app_usage_off),
            icon = Icons.Outlined.Apps,
            modifier = Modifier.toggleable(value = state.appUsageEnabled, role = Role.Switch, onValueChange = onToggle),
            trailing = { Switch(checked = state.appUsageEnabled, onCheckedChange = null) },
        )
    }
    if (state.appUsageEnabled && !hasAccess) {
        item(key = "access") {
            OwlListRow(
                title = stringResource(R.string.app_usage_needs_access),
                icon = Icons.Outlined.WarningAmber,
                titleColor = MaterialTheme.colorScheme.error,
                iconTint = MaterialTheme.colorScheme.error,
                onClick = onAskAccess,
            )
        }
    }
    item(key = "divider") { OwlDivider(Modifier.padding(top = 4.dp)) }
}

private fun LazyListScope.appRows(state: StatsUiState, formats: OwlFormats) {
    val apps = state.apps ?: return
    if (apps.isEmpty()) {
        item(key = "apps-empty") {
            EmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.app_usage_empty_title),
                body = stringResource(if (state.appUsageEnabled) R.string.app_usage_empty_on_body else R.string.app_usage_empty_off_body),
            )
        }
    } else {
        item(key = "apps-total") {
            TotalLine(stringResource(R.string.app_usage_total, formats.duration(apps.sumOf { it.totalMs }, estimate = false)))
        }
        items(apps, key = { it.packageName }) { app ->
            OwlListRow(
                title = app.label,
                subtitle = stringResource(R.string.app_usage_sessions, app.sessions),
                trailing = { DurationLabel(formats.duration(app.totalMs, estimate = false)) },
            )
        }
    }
    item(key = "apps-hint") {
        HintCard(stringResource(R.string.app_usage_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Preview(showBackground = true, widthDp = 390, heightDp = 700, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatsPreview() {
    OwlTheme {
        StatsContent(
            state = StatsUiState(
                period = StatsPeriod(StatsSpan.WEEK, LocalDate.of(2026, 11, 7)),
                places = listOf(
                    PlaceSummary(Place("공학관 4층", 37.58, 127.03), 12, 5_400_000),
                    PlaceSummary(Place(null, 37.57, 127.02), 3, 720_000),
                ),
                apps = listOf(AppUsageSummary("com.example.chat", "메신저", 4_500_000, 23)),
                appUsageEnabled = true,
                hasAppUsage = true,
            ),
            hasAccess = true,
            onSpan = {}, onPrevious = {}, onNext = {}, onToggleAppUsage = {}, onAskAccess = {}, onDeleteAppUsage = {},
        )
    }
}
