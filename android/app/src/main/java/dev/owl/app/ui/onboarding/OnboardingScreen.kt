package dev.owl.app.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.ui.components.HintCard
import dev.owl.app.ui.components.OwlPermissions
import dev.owl.app.ui.components.PendantIcon
import dev.owl.app.ui.theme.OwlTheme

/** Live status of what automatic sync needs (A2, A5). */
data class PermissionStatus(
    val nearby: Boolean = false,
    val notifications: Boolean = false,
    val foregroundLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val batteryExempt: Boolean = false,
) {
    val remaining: Int
        get() = listOf(nearby, notifications, backgroundLocation, batteryExempt).count { !it }

    companion object {
        fun read(context: Context) = PermissionStatus(
            nearby = OwlPermissions.hasNearby(context),
            notifications = OwlPermissions.hasNotifications(context),
            foregroundLocation = OwlPermissions.hasForegroundLocation(context),
            backgroundLocation = OwlPermissions.hasBackgroundLocation(context),
            batteryExempt = OwlPermissions.ignoresBatteryOptimizations(context),
        )
    }
}

private fun Context.startSafely(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        if (fallback != null) {
            try {
                startActivity(fallback)
            } catch (_: ActivityNotFoundException) {
                // Nothing to open on this phone; the row keeps showing what is missing.
            }
        }
    }
}

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit, autoRegister: Boolean = false) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val snackbar = remember { SnackbarHostState() }

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permissions = remember(refresh) { PermissionStatus.read(context) }

    // Permissions asked at least once; if the system then stops showing its dialog we open app settings instead.
    var asked by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val onResult: (Any?) -> Unit = { refresh++ }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), onResult)
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)

    fun request(perms: Array<String>) {
        val blocked = activity != null && perms.any { it in asked } &&
            perms.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked) {
            context.startSafely(OwlPermissions.appDetailsIntent(context))
        } else {
            asked = asked + perms
            if (perms.size == 1) singleLauncher.launch(perms[0]) else multiLauncher.launch(perms)
        }
    }

    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onAssociationResult(result.data)
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is OnboardingEvent.LaunchChooser -> chooserLauncher.launch(IntentSenderRequest.Builder(e.sender).build())
            }
        }
    }
    // "다시 등록" from the device screen: open the chooser once, not again after rotation.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoRegister) {
        if (autoRegister && !autoStarted) {
            autoStarted = true
            viewModel.startAssociation()
        }
    }

    OnboardingContent(
        state = state,
        permissions = permissions,
        snackbar = snackbar,
        onRegister = viewModel::startAssociation,
        onRegisterFake = viewModel::registerFakeDevice,
        onNearby = { request(OwlPermissions.nearby) },
        onNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                context.startSafely(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    OwlPermissions.appDetailsIntent(context),
                )
            }
        },
        onLocation = {
            // Android 11+: "항상 허용" must be asked separately, after foreground location; the system
            // answers that request by opening the app's location settings page.
            if (!permissions.foregroundLocation) {
                request(OwlPermissions.foregroundLocation)
            } else {
                request(arrayOf(OwlPermissions.BACKGROUND_LOCATION))
            }
        },
        onBattery = {
            context.startSafely(
                OwlPermissions.batteryOptimizationIntent(context),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        },
        onLater = { viewModel.finish(onDone) },
        onStart = { viewModel.finish(onDone) },
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    permissions: PermissionStatus,
    snackbar: SnackbarHostState,
    onRegister: () -> Unit,
    onRegisterFake: () -> Unit,
    onNearby: () -> Unit,
    onNotifications: () -> Unit,
    onLocation: () -> Unit,
    onBattery: () -> Unit,
    onLater: () -> Unit,
    onStart: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onLater) { Text(stringResource(R.string.action_later)) }
                    Button(onClick = onStart, enabled = state.registeredName != null) { Text(stringResource(R.string.onboarding_start)) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.padding(bottom = 4.dp).size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { PendantIcon(tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Text(
                    stringResource(R.string.onboarding_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.onboarding_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RegistrationCard(state, onRegister, onRegisterFake)

            Column {
                Text(
                    if (permissions.remaining == 0) {
                        stringResource(R.string.onboarding_permissions_done)
                    } else {
                        stringResource(R.string.onboarding_permissions_left, permissions.remaining)
                    },
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PermissionRow(
                    Icons.Outlined.Bluetooth, R.string.perm_nearby, R.string.perm_nearby_body,
                    granted = permissions.nearby, action = R.string.action_allow, onClick = onNearby,
                )
                PermissionRow(
                    Icons.Outlined.Notifications, R.string.perm_notifications, R.string.perm_notifications_body,
                    granted = permissions.notifications, action = R.string.action_allow, onClick = onNotifications,
                )
                PermissionRow(
                    Icons.Outlined.LocationOn, R.string.perm_location,
                    if (permissions.foregroundLocation && !permissions.backgroundLocation) R.string.perm_location_pick_always else R.string.perm_location_body,
                    granted = permissions.backgroundLocation,
                    action = if (permissions.foregroundLocation) R.string.action_open_settings else R.string.action_allow,
                    onClick = onLocation,
                )
                PermissionRow(
                    Icons.Outlined.BatterySaver, R.string.perm_battery, R.string.perm_battery_body,
                    granted = permissions.batteryExempt, action = R.string.action_allow, onClick = onBattery,
                )
            }

            HintCard(stringResource(R.string.onboarding_samsung_hint))
        }
    }
}

@Composable
private fun RegistrationCard(state: OnboardingUiState, onRegister: () -> Unit, onRegisterFake: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = state.registeredName
    Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(40.dp).background(if (name != null) colors.primary else colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    PendantIcon(tint = if (name != null) colors.onPrimary else colors.onSecondaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name ?: stringResource(R.string.onboarding_no_device), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            name == null -> stringResource(R.string.onboarding_no_device_body)
                            state.firmwareVersion != null -> stringResource(R.string.onboarding_registered_fw, state.firmwareVersion)
                            else -> stringResource(R.string.onboarding_registered)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (name != null) Icon(Icons.Filled.CheckCircle, null, tint = colors.primary)
            }
            if (name == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRegister, enabled = !state.busy) { Text(stringResource(R.string.onboarding_register)) }
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                val error = state.registerError
                if (error != null) {
                    Text(
                        error.ifEmpty { stringResource(R.string.onboarding_register_failed) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
                if (state.fakeDeviceAvailable) {
                    TextButton(onClick = onRegisterFake, enabled = !state.busy) { Text(stringResource(R.string.onboarding_fake_device)) }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: Int, body: Int, granted: Boolean, action: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            FilledTonalButton(onClick = onClick) { Text(stringResource(action)) }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingPreview() {
    OwlTheme {
        OnboardingContent(
            state = OnboardingUiState(registeredName = "Owl-3F2A", firmwareVersion = "0.1.0", fakeDeviceAvailable = true),
            permissions = PermissionStatus(nearby = true, notifications = true, foregroundLocation = true),
            snackbar = remember { SnackbarHostState() },
            onRegister = {}, onRegisterFake = {}, onNearby = {}, onNotifications = {}, onLocation = {}, onBattery = {}, onLater = {}, onStart = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingUnregisteredPreview() {
    OwlTheme {
        OnboardingContent(
            state = OnboardingUiState(fakeDeviceAvailable = true),
            permissions = PermissionStatus(),
            snackbar = remember { SnackbarHostState() },
            onRegister = {}, onRegisterFake = {}, onNearby = {}, onNotifications = {}, onLocation = {}, onBattery = {}, onLater = {}, onStart = {},
        )
    }
}
