package dev.owl.app.ui.places

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.NamedPlace
import dev.owl.app.model.SampleData
import dev.owl.app.ui.components.BackTopBar
import dev.owl.app.ui.components.EmptyState
import dev.owl.app.ui.components.HintCard
import dev.owl.app.ui.components.OwlListRow
import dev.owl.app.ui.theme.OwlTheme

@Composable
fun PlacesScreen(viewModel: PlacesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.failureFlow.collect { snackbar.showSnackbar(context.getString(R.string.places_change_failed)) }
    }
    PlacesContent(state, snackbar, onBack, onRename = viewModel::rename, onDelete = viewModel::delete)
}

@Composable
fun PlacesContent(
    state: PlacesUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRename: (id: Long, name: String) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    // Ids rather than objects so the dialogs survive rotation.
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val places = state.places

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.places_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                places == null -> Unit
                places.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Place,
                    title = stringResource(R.string.places_empty_title),
                    body = stringResource(R.string.places_empty_body),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "intro") {
                        Text(
                            stringResource(R.string.places_intro),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(places, key = { it.id }) { place ->
                        PlaceRow(place, onRename = { renaming = place.id }, onDelete = { deleting = place.id })
                    }
                    item(key = "hint") {
                        HintCard(stringResource(R.string.places_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                    }
                }
            }
        }
    }

    places?.firstOrNull { it.id == renaming }?.let { place ->
        PlaceNameDialog(
            title = stringResource(R.string.place_name_rename),
            initialName = place.name,
            coordinates = null,
            onDismiss = { renaming = null },
            onSave = {
                renaming = null
                onRename(place.id, it)
            },
        )
    }

    places?.firstOrNull { it.id == deleting }?.let { place ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.places_delete_title, place.name)) },
            text = { Text(stringResource(R.string.places_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDelete(place.id)
                    },
                ) { Text(stringResource(R.string.places_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PlaceRow(place: NamedPlace, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    OwlListRow(
        title = place.name,
        subtitle = stringResource(R.string.places_entry_count, place.entryCount),
        icon = Icons.Outlined.Place,
        onClick = onRename,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.place_name_rename)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.places_delete)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Preview(showBackground = true, widthDp = 390, heightDp = 700, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlacesPreview() {
    OwlTheme {
        PlacesContent(PlacesUiState(SampleData.places), remember { SnackbarHostState() }, {}, { _, _ -> }, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 500)
@Composable
private fun PlacesEmptyPreview() {
    OwlTheme {
        PlacesContent(PlacesUiState(emptyList()), remember { SnackbarHostState() }, {}, { _, _ -> }, {})
    }
}
