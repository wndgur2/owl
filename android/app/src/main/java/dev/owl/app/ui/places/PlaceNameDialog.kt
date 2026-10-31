package dev.owl.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.owl.app.R
import dev.owl.app.ui.theme.OwlTheme

/** Longest name we accept; long enough for "학생회관 2층 카페" and friends, short enough for a header. */
const val PLACE_NAME_MAX = 30

/**
 * Text-field dialog for naming or renaming a place (Entry menu, Entry place chip, Places screen).
 * [coordinates] ("37.5665, 126.9780") is shown as detail when the place has no name yet.
 */
@Composable
fun PlaceNameDialog(
    title: String,
    initialName: String?,
    coordinates: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = initialName.orEmpty()
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    val name = value.text.trim()
    val canSave = name.isNotEmpty() && name != initialName
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (coordinates != null) {
                        stringResource(R.string.place_name_body_coordinates, coordinates)
                    } else {
                        stringResource(R.string.place_name_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.text.length <= PLACE_NAME_MAX) value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.place_name_label)) },
                    placeholder = { Text(stringResource(R.string.place_name_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(name) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = canSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun PlaceNameDialogPreview() {
    OwlTheme {
        PlaceNameDialog(
            title = stringResource(R.string.place_name_add),
            initialName = null,
            coordinates = "37.5665, 126.9780",
            onDismiss = {},
            onSave = {},
        )
    }
}
