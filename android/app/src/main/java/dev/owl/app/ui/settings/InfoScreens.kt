package dev.owl.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.owl.app.R
import dev.owl.app.ui.components.BackTopBar
import dev.owl.app.ui.components.OwlDivider
import dev.owl.app.ui.components.SectionTitle
import dev.owl.app.ui.theme.OwlTheme

/** Settings › 개인정보 처리 방침 (PRD N8). Static text, so no ViewModel. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.privacy_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.PrivacyTip, null)
                    Text(stringResource(R.string.privacy_principle), style = MaterialTheme.typography.titleMedium)
                }
            }
            InfoItem(Icons.Outlined.CloudOff, R.string.privacy_no_upload_title, R.string.privacy_no_upload_body)
            InfoItem(Icons.Outlined.PhoneAndroid, R.string.privacy_on_device_title, R.string.privacy_on_device_body)
            InfoItem(Icons.Outlined.LocationOn, R.string.privacy_location_title, R.string.privacy_location_body)
            InfoItem(Icons.Outlined.SettingsBackupRestore, R.string.privacy_backup_title, R.string.privacy_backup_body)
            InfoItem(Icons.Outlined.FileDownload, R.string.privacy_export_title, R.string.privacy_export_body)
        }
    }
}

@Composable
private fun InfoItem(icon: ImageVector, title: Int, body: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Settings › 사용 설명 (PRD A16): buttons (D7, D8, D8a), LED patterns (decisions.md #21), storage care. */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.guide_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.guide_section_button))
            GuideRow(R.string.guide_press_short, R.string.guide_press_short_does, R.string.guide_press_short_body)
            GuideRow(R.string.guide_press_2s, R.string.guide_press_2s_does, R.string.guide_press_2s_body)
            GuideRow(R.string.guide_press_5s, R.string.guide_press_5s_does, R.string.guide_press_5s_body)

            OwlDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.guide_section_led))
            Text(
                stringResource(R.string.guide_led_intro),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LED_ROWS.forEach { (situation, pattern) -> LedRow(situation, pattern) }

            OwlDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.guide_section_battery))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.guide_store_charged), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.guide_store_charged_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.guide_auto_on_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val LED_ROWS = listOf(
    R.string.led_recording to R.string.led_recording_pattern,
    R.string.led_bookmark to R.string.led_bookmark_pattern,
    R.string.led_holding to R.string.led_holding_pattern,
    R.string.led_recording_toggle to R.string.led_recording_toggle_pattern,
    R.string.led_power_toggle to R.string.led_power_toggle_pattern,
    R.string.led_battery_low to R.string.led_battery_low_pattern,
    R.string.led_storage_full to R.string.led_storage_full_pattern,
)

@Composable
private fun GuideRow(press: Int, does: Int, body: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.width(64.dp)) {
            Text(
                stringResource(press),
                modifier = Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(does), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LedRow(situation: Int, pattern: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50), modifier = Modifier.size(8.dp)) {}
        Text(stringResource(situation), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(pattern),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun GuidePreview() {
    OwlTheme { GuideScreen {} }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun PrivacyPreview() {
    OwlTheme { PrivacyScreen {} }
}
