package dev.owl.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.AudioUsage
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.ui.components.BackTopBar
import dev.owl.app.ui.components.HintCard
import dev.owl.app.ui.components.OwlDivider
import dev.owl.app.ui.components.OwlListRow
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.theme.OwlTheme

@Composable
fun RetentionScreen(viewModel: RetentionViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RetentionContent(state, onBack, viewModel::setPolicy)
    val pending = state.pending
    if (pending != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text(stringResource(R.string.retention_confirm_title, pending.entries)) },
            text = { Text(stringResource(R.string.retention_confirm_body, policyLabel(pending.policy), pending.entries)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirm) {
                    Text(stringResource(R.string.retention_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun policyLabel(p: RetentionPolicy): String = stringResource(
    when (p) {
        RetentionPolicy.DAYS_7 -> R.string.retention_7
        RetentionPolicy.DAYS_30 -> R.string.retention_30
        RetentionPolicy.DAYS_90 -> R.string.retention_90
        RetentionPolicy.NEVER -> R.string.retention_never
        RetentionPolicy.AFTER_TRANSCRIPTION -> R.string.retention_after_stt
    },
)

@Composable
fun RetentionContent(state: RetentionUiState, onBack: () -> Unit, onSelect: (RetentionPolicy) -> Unit) {
    val formats = rememberOwlFormats()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.retention_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.retention_intro),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                RetentionPolicy.entries.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = state.policy == p, role = Role.RadioButton, onClick = { onSelect(p) })
                            .defaultMinSize(minHeight = 56.dp)
                            .padding(start = 16.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = state.policy == p, onClick = null)
                        Text(policyLabel(p), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            OwlDivider(Modifier.padding(vertical = 8.dp))
            val usage = state.usage
            OwlListRow(
                title = stringResource(R.string.retention_usage_title),
                subtitle = stringResource(R.string.retention_usage_body, usage?.entryCount ?: 0),
                icon = Icons.Outlined.Storage,
                trailing = {
                    Text(
                        if (usage != null) formats.bytes(usage.bytes) else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HintCard(stringResource(R.string.retention_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp))
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun RetentionPreview() {
    OwlTheme { RetentionContent(RetentionUiState(RetentionPolicy.DAYS_30, AudioUsage(126, 412L * 1024 * 1024)), {}, {}) }
}
