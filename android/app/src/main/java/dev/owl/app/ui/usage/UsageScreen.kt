package dev.owl.app.ui.usage

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.AppUsageSummary
import dev.owl.app.ui.components.BackTopBar
import dev.owl.app.ui.components.EmptyState
import dev.owl.app.ui.components.HintCard
import dev.owl.app.ui.components.OwlDivider
import dev.owl.app.ui.components.OwlListRow
import dev.owl.app.ui.components.OwlPermissions
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.theme.OwlTheme
import java.time.LocalDate

/** Settings › 앱 사용 기록 (A18): the switch, then each app's time on the chosen day. */
@Composable
fun UsageScreen(viewModel: UsageViewModel, onBack: () -> Unit) {
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
            if (viewModel.hasAccess()) viewModel.setEnabled(true)
        }
        viewModel.onScreenShown()
    }
    val hasAccess = remember(resumes) { viewModel.hasAccess() }

    UsageContent(
        state = state,
        hasAccess = hasAccess,
        onBack = onBack,
        onToggle = { on ->
            when {
                !on -> viewModel.setEnabled(false)
                hasAccess -> viewModel.setEnabled(true)
                else -> askingAccess = true
            }
        },
        onAskAccess = { askingAccess = true },
        onPreviousDay = viewModel::previousDay,
        onNextDay = viewModel::nextDay,
        onDeleteAll = viewModel::deleteAll,
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
                            enableOnReturn = !state.enabled
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

@Composable
fun UsageContent(
    state: UsageUiState,
    hasAccess: Boolean,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAskAccess: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val formats = rememberOwlFormats()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.app_usage_title), onBack) {
                if (state.hasRecords) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.app_usage_delete))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "switch") {
                OwlListRow(
                    title = stringResource(R.string.app_usage_title),
                    subtitle = stringResource(if (state.enabled) R.string.app_usage_on else R.string.app_usage_off),
                    icon = Icons.Outlined.Apps,
                    modifier = Modifier.toggleable(value = state.enabled, role = Role.Switch, onValueChange = onToggle),
                    trailing = { Switch(checked = state.enabled, onCheckedChange = null) },
                )
            }
            if (state.enabled && !hasAccess) {
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
            item(key = "divider") { OwlDivider(Modifier.padding(vertical = 4.dp)) }
            item(key = "date") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousDay) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.app_usage_previous_day))
                    }
                    Text(
                        formats.dayLong(state.date),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = onNextDay, enabled = !state.isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.app_usage_next_day))
                    }
                }
            }
            val apps = state.apps
            when {
                apps == null -> Unit
                apps.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Apps,
                        title = stringResource(R.string.app_usage_empty_title),
                        body = stringResource(if (state.enabled) R.string.app_usage_empty_on_body else R.string.app_usage_empty_off_body),
                    )
                }
                else -> {
                    item(key = "total") {
                        Text(
                            stringResource(R.string.app_usage_total, formats.duration(apps.sumOf { it.totalMs }, estimate = false)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    items(apps, key = { it.packageName }) { app ->
                        OwlListRow(
                            title = app.label,
                            subtitle = stringResource(R.string.app_usage_sessions, app.sessions),
                            trailing = {
                                Text(
                                    formats.duration(app.totalMs, estimate = false),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
            item(key = "hint") {
                HintCard(stringResource(R.string.app_usage_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
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
                        onDeleteAll()
                    },
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Preview(showBackground = true, widthDp = 390, heightDp = 700, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UsagePreview() {
    OwlTheme {
        UsageContent(
            state = UsageUiState(
                enabled = true,
                date = LocalDate.of(2026, 11, 7),
                apps = listOf(
                    AppUsageSummary("com.example.chat", "메신저", 4_500_000, 23),
                    AppUsageSummary("com.example.map", "지도", 720_000, 3),
                ),
                hasRecords = true,
            ),
            hasAccess = true,
            onBack = {}, onToggle = {}, onAskAccess = {}, onPreviousDay = {}, onNextDay = {}, onDeleteAll = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 500)
@Composable
private fun UsageOffPreview() {
    OwlTheme {
        UsageContent(
            state = UsageUiState(date = LocalDate.of(2026, 11, 7), apps = emptyList()),
            hasAccess = false,
            onBack = {}, onToggle = {}, onAskAccess = {}, onPreviousDay = {}, onNextDay = {}, onDeleteAll = {},
        )
    }
}
