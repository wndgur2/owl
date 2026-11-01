package dev.owl.app.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.Connection
import dev.owl.app.model.DeviceUiState
import dev.owl.app.model.SampleData
import dev.owl.app.model.SyncProgress
import dev.owl.app.ui.components.OwlDivider
import dev.owl.app.ui.components.OwlFormats
import dev.owl.app.ui.components.OwlListRow
import dev.owl.app.ui.components.PendantIcon
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.components.toLocalDate
import dev.owl.app.ui.components.today
import dev.owl.app.ui.theme.OwlTheme

@Composable
fun DeviceScreen(viewModel: DeviceViewModel, onOpenSettings: () -> Unit, onRegister: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.device_recording_failed)
    LaunchedEffect(viewModel) {
        viewModel.recordingFailed.collect { snackbar.showSnackbar(failed) }
    }
    DeviceContent(
        state = state,
        snackbar = snackbar,
        onOpenSettings = onOpenSettings,
        onSync = viewModel::syncNow,
        onReconnect = viewModel::reconnect,
        onSetRecording = viewModel::setRecording,
        onRegister = onRegister,
    )
}

private val dash = "—"

@Composable
fun DeviceContent(
    state: DeviceUiState,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit,
    onReconnect: () -> Unit,
    onSetRecording: (Boolean) -> Unit,
    onRegister: () -> Unit,
) {
    val formats = rememberOwlFormats()
    Scaffold(contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tab_device),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.registered) {
                    NotRegisteredCard(onRegister)
                } else {
                    PendantCard(state, formats, onReconnect, onSetRecording)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BatteryCard(state, Modifier.weight(1f).fillMaxHeight())
                        StorageCard(state, formats, Modifier.weight(1f).fillMaxHeight())
                    }
                    SyncCard(state, formats, onSync)
                    Card {
                        OwlListRow(
                            title = stringResource(R.string.device_firmware),
                            icon = Icons.Outlined.Memory,
                            trailing = { TrailingValue(state.firmwareVersion ?: dash) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(12.dp)) {
        Column { content() }
    }
}

@Composable
private fun TrailingValue(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun NotRegisteredCard(onRegister: () -> Unit) {
    Card(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.device_not_registered), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.device_not_registered_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRegister) { Text(stringResource(R.string.onboarding_register)) }
        }
    }
}

@Composable
private fun PendantCard(
    state: DeviceUiState,
    formats: OwlFormats,
    onReconnect: () -> Unit,
    onSetRecording: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (powerLabel, dot, labelColor) = when (state.connection) {
        Connection.CONNECTED -> Triple(R.string.power_on, colors.primary, colors.onSurfaceVariant)
        Connection.DISCONNECTED -> Triple(R.string.power_disconnected, colors.error, colors.error)
        Connection.CONNECTING -> Triple(R.string.power_connecting, colors.outline, colors.onSurfaceVariant)
        Connection.UNKNOWN -> Triple(R.string.power_unknown, colors.outline, colors.onSurfaceVariant)
        Connection.NOT_PAIRED -> Triple(R.string.power_not_paired, colors.error, colors.error)
    }
    Card(color = colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                PendantIcon(tint = colors.onPrimaryContainer)
            }
            Text(
                state.name ?: dash,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(stringResource(powerLabel), style = MaterialTheme.typography.labelLarge, color = labelColor)
            }
        }
        if (state.connection == Connection.DISCONNECTED) {
            val seen = state.lastSeenAtMs
            Text(
                if (seen != null) {
                    stringResource(R.string.device_no_answer_since, if (seen.toLocalDate() == today()) formats.hm(seen) else formats.relativeDayTime(seen))
                } else {
                    stringResource(R.string.device_no_answer)
                },
                modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onReconnect, modifier = Modifier.padding(start = 64.dp, top = 8.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.device_connect))
            }
        }

        if (state.connection == Connection.NOT_PAIRED) {
            Text(
                stringResource(R.string.device_not_paired),
                modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }

        // Recording state, separate from power (A10), with the SET_RECORDING switch.
        val recording = state.recordingOn
        val canToggle = recording != null && state.connection == Connection.CONNECTED && !state.changingRecording
        OwlDivider(Modifier.padding(top = 4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = recording == true,
                    enabled = canToggle,
                    role = Role.Switch,
                    onValueChange = onSetRecording,
                )
                .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                if (recording == false) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.device_recording), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        when (recording) {
                            true -> R.string.recording_on
                            false -> R.string.recording_off
                            null -> R.string.recording_unknown
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (recording == false) colors.tertiary else colors.onSurfaceVariant,
                )
            }
            Switch(checked = recording == true, onCheckedChange = null, enabled = canToggle)
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.secondaryContainer,
    )
}

@Composable
private fun BatteryCard(state: DeviceUiState, modifier: Modifier) {
    val pct = state.batteryPercent
    StatCard(Icons.Outlined.BatteryFull, stringResource(R.string.device_battery), modifier) {
        Text(if (pct != null) stringResource(R.string.percent, pct) else dash, style = MaterialTheme.typography.headlineLarge)
        val warn = state.batteryLow || (pct != null && pct <= 15)
        Meter((pct ?: 0) / 100f, if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        val hours = state.batteryHoursLeft
        Text(
            if (hours != null) stringResource(R.string.device_battery_left, hours) else dash,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.batteryLow) {
            Text(
                stringResource(R.string.device_battery_low),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StorageCard(state: DeviceUiState, formats: OwlFormats, modifier: Modifier) {
    val used = state.storageUsedMb
    val total = state.storageTotalMb
    val warn = state.storageFull || state.storageLow
    StatCard(Icons.Outlined.SdStorage, stringResource(R.string.device_storage), modifier) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (used != null) formats.megabytes(used) else dash, style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.device_storage_total, if (total != null) formats.megabytes(total) else dash),
                modifier = Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Meter(
            if (used != null && total != null && total > 0) (used / total).toFloat() else 0f,
            if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        if (warn) {
            Text(
                stringResource(if (state.storageFull) R.string.device_storage_full else R.string.device_storage_low),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SyncCard(state: DeviceUiState, formats: OwlFormats, onSync: () -> Unit) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.device_sync),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FilledTonalButton(
                onClick = onSync,
                enabled = !state.syncing,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Text(
                    stringResource(if (state.syncing) R.string.device_syncing else R.string.device_sync_now),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (state.syncing) SyncProgressRow(state.progress)
        val last = state.lastSyncAtMs
        OwlListRow(
            title = stringResource(R.string.device_last_sync),
            icon = Icons.Outlined.Schedule,
            trailing = { TrailingValue(if (last != null) formats.relativeDayTime(last) else stringResource(R.string.device_never_synced)) },
        )
        OwlDivider()
        val pending = state.pendingSegments
        OwlListRow(
            title = stringResource(R.string.device_pending),
            subtitle = if (pending != null && pending > 0) stringResource(R.string.device_pending_body) else null,
            icon = Icons.Outlined.HourglassEmpty,
            trailing = { TrailingValue(if (pending != null) stringResource(R.string.count_items, pending) else dash) },
        )
    }
}

@Composable
private fun SyncProgressRow(progress: SyncProgress?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null || progress.segmentsTotal == 0) {
            Text(stringResource(R.string.device_sync_connecting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val current = (progress.segmentsDone + 1).coerceAtMost(progress.segmentsTotal)
            Text(
                stringResource(R.string.device_sync_progress, current, progress.segmentsTotal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val overall = (progress.segmentsDone + progress.currentFraction) / progress.segmentsTotal
            LinearProgressIndicator(progress = { overall.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
private fun DevicePreview() {
    OwlTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DeviceContent(SampleData.device, remember { SnackbarHostState() }, {}, {}, {}, {}, {})
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 760, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DeviceDisconnectedPreview() {
    OwlTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            DeviceContent(
                SampleData.device.copy(
                    connection = Connection.DISCONNECTED, recordingOn = false, storageLow = true,
                    syncing = true, progress = SyncProgress(1, 4, 0.5f),
                ),
                remember { SnackbarHostState() }, {}, {}, {}, {}, {},
            )
        }
    }
}
