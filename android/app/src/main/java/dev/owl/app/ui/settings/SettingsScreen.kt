package dev.owl.app.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.ThemeMode
import dev.owl.app.ui.components.BackTopBar
import dev.owl.app.ui.components.OwlDivider
import dev.owl.app.ui.components.OwlListRow
import dev.owl.app.ui.components.OwlPermissions
import dev.owl.app.ui.components.SectionTitle
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.components.today
import dev.owl.app.ui.theme.OwlTheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenRetention: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenGuide: () -> Unit,
    onUnregistered: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Permission status is re-read whenever we come back (e.g. from the system settings page).
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val hasBackground = remember(resumes) { OwlPermissions.hasBackgroundLocation(context) }
    var showBackgroundDialog by rememberSaveable { mutableStateOf(false) }

    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            viewModel.setLocationEnabled(true)
            if (!OwlPermissions.hasBackgroundLocation(context)) showBackgroundDialog = true
        }
        resumes++
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val exportName = stringResource(R.string.export_file_name, today().toString())

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is SettingsEvent.Exported -> snackbar.showSnackbar(context.getString(R.string.export_done, e.count))
                SettingsEvent.ExportFailed -> snackbar.showSnackbar(context.getString(R.string.export_failed))
            }
        }
    }

    SettingsContent(
        state = state,
        backgroundLocationGranted = hasBackground,
        snackbar = snackbar,
        onBack = onBack,
        onOpenRetention = onOpenRetention,
        onOpenPlaces = onOpenPlaces,
        onToggleLocation = { on ->
            when {
                !on -> viewModel.setLocationEnabled(false)
                !OwlPermissions.hasForegroundLocation(context) -> foregroundLauncher.launch(OwlPermissions.foregroundLocation)
                else -> {
                    viewModel.setLocationEnabled(true)
                    if (!OwlPermissions.hasBackgroundLocation(context)) showBackgroundDialog = true
                }
            }
        },
        onExport = { exportLauncher.launch(exportName) },
        onOpenPrivacy = onOpenPrivacy,
        onOpenGuide = onOpenGuide,
        onSelectTheme = viewModel::setThemeMode,
        onUnregister = { viewModel.unregister(onUnregistered) },
    )

    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            title = { Text(stringResource(R.string.location_always_title)) },
            text = { Text(stringResource(R.string.location_always_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundDialog = false
                        try {
                            context.startActivity(OwlPermissions.appDetailsIntent(context))
                        } catch (_: ActivityNotFoundException) {
                            // No settings app (very unusual); the subtitle still says what is missing.
                        }
                    },
                ) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showBackgroundDialog = false }) { Text(stringResource(R.string.action_later)) } },
        )
    }
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    backgroundLocationGranted: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenRetention: () -> Unit,
    onOpenPlaces: () -> Unit,
    onToggleLocation: (Boolean) -> Unit,
    onExport: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenGuide: () -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onUnregister: () -> Unit,
) {
    val formats = rememberOwlFormats()
    var confirmUnregister by rememberSaveable { mutableStateOf(false) }
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    val chevron: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_section_storage))
            OwlListRow(
                title = stringResource(R.string.retention_title),
                subtitle = state.retention?.let { formats.retention(it) },
                icon = Icons.Outlined.AutoDelete,
                onClick = onOpenRetention,
                trailing = chevron,
            )
            OwlListRow(
                title = stringResource(R.string.location_title),
                subtitle = stringResource(
                    when {
                        !state.locationEnabled -> R.string.location_off
                        backgroundLocationGranted -> R.string.location_on_always
                        else -> R.string.location_on_needs_always
                    },
                ),
                icon = Icons.Outlined.LocationOn,
                modifier = Modifier.toggleable(value = state.locationEnabled, role = Role.Switch, onValueChange = onToggleLocation),
                trailing = { Switch(checked = state.locationEnabled, onCheckedChange = null) },
            )
            OwlListRow(
                title = stringResource(R.string.places_title),
                subtitle = when (val n = state.namedPlaceCount) {
                    null -> null
                    0 -> stringResource(R.string.places_summary_none)
                    else -> stringResource(R.string.places_summary, n)
                },
                icon = Icons.Outlined.Place,
                onClick = onOpenPlaces,
                trailing = chevron,
            )

            OwlDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle(stringResource(R.string.settings_section_data))
            OwlListRow(
                title = stringResource(R.string.export_title),
                subtitle = stringResource(R.string.export_body),
                icon = Icons.Outlined.FileDownload,
                enabled = !state.exporting,
                onClick = onExport,
                trailing = if (state.exporting) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    chevron
                },
            )
            OwlListRow(
                title = stringResource(R.string.privacy_title),
                subtitle = stringResource(R.string.privacy_principle),
                icon = Icons.Outlined.PrivacyTip,
                onClick = onOpenPrivacy,
                trailing = chevron,
            )

            OwlDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle(stringResource(R.string.settings_section_display))
            OwlListRow(
                title = stringResource(R.string.theme_title),
                subtitle = state.themeMode?.let { themeLabel(it) },
                icon = Icons.Outlined.DarkMode,
                onClick = { choosingTheme = true },
                trailing = chevron,
            )

            OwlDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle(stringResource(R.string.settings_section_help))
            OwlListRow(
                title = stringResource(R.string.guide_title),
                subtitle = stringResource(R.string.guide_summary),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                onClick = onOpenGuide,
                trailing = chevron,
            )

            if (state.fakeDeviceAvailable) {
                OwlDivider(Modifier.padding(vertical = 4.dp))
                SectionTitle(stringResource(R.string.settings_section_developer))
                OwlListRow(
                    title = stringResource(if (state.usingFakeDevice) R.string.dev_fake_in_use else R.string.dev_real_in_use),
                    subtitle = stringResource(R.string.dev_debug_only),
                    icon = Icons.Outlined.BugReport,
                )
            }

            if (state.registeredName != null) {
                OwlDivider(Modifier.padding(vertical = 4.dp))
                OwlListRow(
                    title = stringResource(R.string.unregister_title),
                    icon = Icons.Outlined.LinkOff,
                    titleColor = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = { confirmUnregister = true },
                )
            }
        }
    }

    if (choosingTheme) {
        AlertDialog(
            onDismissRequest = { choosingTheme = false },
            title = { Text(stringResource(R.string.theme_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.themeMode == mode,
                                    role = Role.RadioButton,
                                    onClick = {
                                        choosingTheme = false
                                        onSelectTheme(mode)
                                    },
                                )
                                .defaultMinSize(minHeight = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            RadioButton(selected = state.themeMode == mode, onClick = null)
                            Text(themeLabel(mode), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingTheme = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // The dialog stays up with a progress row until unregistering is done (decisions #47): UNPAIR can
    // wait 15 s for a pendant that is away, and a screen that looked unchanged invited a second tap.
    if (confirmUnregister || state.unregistering) {
        AlertDialog(
            onDismissRequest = { if (!state.unregistering) confirmUnregister = false },
            title = { Text(stringResource(R.string.unregister_confirm_title, state.registeredName.orEmpty())) },
            text = {
                if (state.unregistering) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.unregistering))
                    }
                } else {
                    Text(stringResource(R.string.unregister_confirm_body))
                }
            },
            confirmButton = {
                TextButton(onClick = onUnregister, enabled = !state.unregistering) {
                    Text(
                        stringResource(R.string.unregister_confirm),
                        color = if (state.unregistering) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnregister = false }, enabled = !state.unregistering) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.SYSTEM -> R.string.theme_system
    },
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsPreview() {
    OwlTheme {
        SettingsContent(
            state = SettingsUiState(
                retention = RetentionPolicy.DAYS_30, locationEnabled = true, registeredName = "Owl-3F2A",
                fakeDeviceAvailable = true, usingFakeDevice = true, namedPlaceCount = 4, themeMode = ThemeMode.DARK,
            ),
            backgroundLocationGranted = true,
            snackbar = remember { SnackbarHostState() },
            onBack = {}, onOpenRetention = {}, onOpenPlaces = {}, onToggleLocation = {}, onExport = {}, onOpenPrivacy = {}, onOpenGuide = {}, onSelectTheme = {}, onUnregister = {},
        )
    }
}
