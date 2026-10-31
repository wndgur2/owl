package dev.owl.app.ui.entry

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owl.app.R
import dev.owl.app.model.EntryDetail
import dev.owl.app.model.PlayerState
import dev.owl.app.model.SampleData
import dev.owl.app.model.SegmentState
import dev.owl.app.model.Sentence
import dev.owl.app.ui.components.BackTopBar
import dev.owl.app.ui.components.EmptyState
import dev.owl.app.ui.components.OwlFormats
import dev.owl.app.ui.components.rememberOwlFormats
import dev.owl.app.ui.components.toLocalDate
import dev.owl.app.ui.places.PlaceNameDialog
import dev.owl.app.ui.theme.OwlTheme
import dev.owl.app.ui.theme.disabledContainer
import dev.owl.app.ui.theme.disabledContent
import kotlinx.coroutines.launch

@Composable
fun EntryScreen(viewModel: EntryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.entry_copied)
    val clipLabel = stringResource(R.string.entry_clip_label)
    val placeFailed = stringResource(R.string.place_name_failed)
    LaunchedEffect(viewModel) {
        viewModel.placeFailureFlow.collect { snackbar.showSnackbar(placeFailed) }
    }
    EntryContent(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onTogglePlay = viewModel::togglePlay,
        onSeek = viewModel::seekTo,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onCycleSpeed = viewModel::cycleSpeed,
        onSetPreserve = viewModel::setPreserve,
        onCopyText = {
            scope.launch {
                val text = viewModel.transcriptText()
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar(copied)
            }
        },
        onDelete = { viewModel.delete(onBack) },
        onNamePlace = viewModel::namePlace,
    )
}

/** Sentence rows interleaved with bookmark marker rows (a marker goes after the sentence it falls in). */
private sealed interface EntryLine {
    data class Text(val sentence: Sentence) : EntryLine
    data class Mark(val offsetMs: Long, val n: Int) : EntryLine
}

private fun buildLines(sentences: List<Sentence>, bookmarks: List<Long>): List<EntryLine> {
    val marks = bookmarks.sorted().toMutableList()
    val out = ArrayList<EntryLine>(sentences.size + marks.size)
    var n = 0
    for (s in sentences) {
        while (marks.isNotEmpty() && marks.first() < s.startMs) out += EntryLine.Mark(marks.removeAt(0), n++)
        out += EntryLine.Text(s)
    }
    marks.forEach { out += EntryLine.Mark(it, n++) }
    return out
}

@Composable
fun EntryContent(
    state: EntryUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSetPreserve: (Boolean) -> Unit,
    onCopyText: () -> Unit,
    onDelete: () -> Unit,
    onNamePlace: (String) -> Unit,
) {
    val formats = rememberOwlFormats()
    val detail = state.detail
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var namingPlace by rememberSaveable { mutableStateOf(false) }

    val title = when {
        detail == null -> ""
        detail.item.startUnixMs == null -> stringResource(R.string.time_unknown)
        else -> stringResource(
            R.string.entry_title,
            formats.monthDay(detail.item.startUnixMs.toLocalDate()),
            formats.hm(detail.item.startUnixMs),
        )
    }

    Scaffold(
        topBar = {
            BackTopBar(title = title, onBack = onBack) {
                if (detail != null) {
                    EntryMenu(
                        detail, formats, onSetPreserve, onCopyText,
                        onNamePlace = { namingPlace = true },
                        onDelete = { confirmDelete = true },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (detail != null) {
                PlayerBar(
                    detail = detail, player = state.player, formats = formats,
                    onTogglePlay = onTogglePlay, onSeek = onSeek, onPrevious = onPrevious, onNext = onNext, onCycleSpeed = onCycleSpeed,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.loaded || state.deleting -> Unit
                detail == null -> EmptyState(
                    icon = Icons.Outlined.Delete,
                    title = stringResource(R.string.entry_missing),
                    body = null,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> EntryBody(detail, state.player, formats, onSeek, onPlaceClick = { namingPlace = true })
            }
        }
    }

    val place = detail?.item?.place
    if (namingPlace && place != null) {
        PlaceNameDialog(
            title = stringResource(if (place.name != null) R.string.place_name_rename else R.string.place_name_add),
            initialName = place.name,
            coordinates = if (place.name == null) formats.coordinates(place.latitude, place.longitude) else null,
            onDismiss = { namingPlace = false },
            onSave = {
                namingPlace = false
                onNamePlace(it)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.entry_delete_title)) },
            text = { Text(stringResource(R.string.entry_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EntryMenu(
    detail: EntryDetail,
    formats: OwlFormats,
    onSetPreserve: (Boolean) -> Unit,
    onCopyText: () -> Unit,
    onNamePlace: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val item = detail.item
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(264.dp)) {
        val deleteAt = detail.audioDeleteAtMs
        val subtitle = when {
            item.audioDeleted -> stringResource(R.string.preserve_already_deleted)
            item.preserveAudio -> stringResource(R.string.preserve_on)
            deleteAt != null -> stringResource(R.string.preserve_delete_at, formats.monthDay(deleteAt.toLocalDate()))
            else -> stringResource(R.string.preserve_never)
        }
        DropdownMenuItem(
            text = {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.preserve_audio), style = MaterialTheme.typography.labelLarge)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.audioDeleted) MaterialTheme.colorScheme.disabledContent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leadingIcon = {
                Icon(if (item.preserveAudio && !item.audioDeleted) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null)
            },
            enabled = !item.audioDeleted,
            onClick = {
                onSetPreserve(!item.preserveAudio)
                open = false
            },
            modifier = Modifier.semantics { selected = item.preserveAudio },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.copy_text)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
            enabled = detail.sentences.isNotEmpty(),
            onClick = {
                onCopyText()
                open = false
            },
        )
        DropdownMenuItem(
            text = {
                Text(stringResource(if (item.place?.name != null) R.string.place_name_rename else R.string.place_name_add))
            },
            leadingIcon = { Icon(Icons.Outlined.EditLocationAlt, null) },
            // No GPS fix for this recording: nothing to name.
            enabled = item.place != null,
            onClick = {
                open = false
                onNamePlace()
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete_entry)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
            onClick = {
                open = false
                onDelete()
            },
        )
    }
}

@Composable
private fun OutlinedTag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.height(32.dp).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val clickable = if (onClick != null) {
        Modifier.clip(shape).clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = clickable,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        content = content,
    )
}

@Composable
private fun EntryBody(
    detail: EntryDetail,
    player: PlayerState,
    formats: OwlFormats,
    onSeek: (Long) -> Unit,
    onPlaceClick: () -> Unit,
) {
    val item = detail.item
    val lines = remember(detail.sentences, detail.bookmarkOffsetsMs) { buildLines(detail.sentences, detail.bookmarkOffsetsMs) }
    val current = detail.sentences.indexOfLast { it.startMs <= player.positionMs }
    val listState = rememberLazyListState()

    // Keep the playing sentence on screen.
    val currentLine = lines.indexOfFirst { it is EntryLine.Text && it.sentence.index == detail.sentences.getOrNull(current)?.index }
    LaunchedEffect(currentLine) {
        if (currentLine < 0 || !player.playing) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == currentLine && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fully) listState.animateScrollToItem(currentLine)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Tapping the place opens the naming dialog; without a GPS fix there is nothing to name.
            val place = item.place
            OutlinedTag(
                Icons.Outlined.Place,
                formats.place(place),
                onClick = if (place != null) onPlaceClick else null,
                onClickLabel = stringResource(if (place?.name != null) R.string.place_name_rename else R.string.place_name_add),
            )
            if (item.bookmarkCount > 0) {
                OutlinedTag(Icons.Outlined.BookmarkBorder, stringResource(R.string.bookmark_count, item.bookmarkCount))
            }
        }
        if (detail.sentences.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (item.state != SegmentState.DONE) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(32.dp), trackColor = MaterialTheme.colorScheme.secondaryContainer)
                        Text(stringResource(R.string.state_transcribing), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (detail.audioFiles.isNotEmpty()) {
                            Text(stringResource(R.string.entry_listen_now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(stringResource(R.string.state_no_speech), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(lines, key = { if (it is EntryLine.Text) "s${it.sentence.index}" else "m${(it as EntryLine.Mark).n}" }) { line ->
                    when (line) {
                        is EntryLine.Mark -> BookmarkMarker(
                            if (item.startUnixMs != null) formats.hms(item.startUnixMs + line.offsetMs) else formats.clock(line.offsetMs),
                        )
                        is EntryLine.Text -> {
                            val isCurrent = detail.sentences.getOrNull(current)?.index == line.sentence.index
                            SentenceRow(
                                sentence = line.sentence,
                                label = if (item.startUnixMs != null) {
                                    formats.hms(item.startUnixMs + line.sentence.startMs)
                                } else {
                                    formats.clock(line.sentence.startMs)
                                },
                                current = isCurrent,
                                playing = isCurrent && player.playing,
                                onClick = { onSeek(line.sentence.startMs) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkMarker(time: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Bookmark, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(stringResource(R.string.bookmark_marker, time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SentenceRow(sentence: Sentence, label: String, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { selected = current },
        shape = RoundedCornerShape(12.dp),
        color = if (current) colors.surfaceContainerHigh else Color.Transparent,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (playing) stringResource(R.string.sentence_playing, label) else label,
                style = MaterialTheme.typography.labelMedium,
                color = if (current) colors.primary else colors.onSurfaceVariant,
            )
            Text(sentence.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PlayerBar(
    detail: EntryDetail,
    player: PlayerState,
    formats: OwlFormats,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleSpeed: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val gone = detail.item.audioDeleted
    val duration = (if (player.durationMs > 0) player.durationMs else detail.item.durationMs).coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = dragging ?: player.positionMs.coerceIn(0, duration).toFloat()
    val hasText = detail.sentences.isNotEmpty()

    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        ) {
            val seekLabel = stringResource(R.string.player_position)
            Slider(
                value = position,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat(),
                colors = if (gone) {
                    SliderDefaults.colors(thumbColor = colors.outline, activeTrackColor = colors.outline)
                } else {
                    SliderDefaults.colors()
                },
                modifier = Modifier.semantics { contentDescription = seekLabel },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formats.clock(position.toLong()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (gone) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.VolumeOff, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                            Text(stringResource(R.string.audio_deleted), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
                Text(formats.clock(duration), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(64.dp)) {
                    if (!gone) {
                        Surface(
                            onClick = onCycleSpeed,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            color = Color.Transparent,
                            contentColor = colors.onSurfaceVariant,
                        ) {
                            Text(
                                stringResource(R.string.player_speed, player.speed),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (hasText) Icons.Filled.SkipPrevious else Icons.Filled.Replay10,
                        contentDescription = stringResource(if (hasText) R.string.player_prev_sentence else R.string.player_back_10),
                        tint = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = onTogglePlay,
                    enabled = player.canPlay,
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        disabledContainerColor = colors.disabledContainer,
                        disabledContentColor = colors.disabledContent,
                    ),
                ) {
                    Icon(
                        when {
                            !player.canPlay -> Icons.AutoMirrored.Outlined.VolumeOff
                            player.playing -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(
                            when {
                                !player.canPlay -> R.string.player_cannot_play
                                player.playing -> R.string.player_pause
                                else -> R.string.player_play
                            },
                        ),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (hasText) Icons.Filled.SkipNext else Icons.Filled.Forward10,
                        contentDescription = stringResource(if (hasText) R.string.player_next_sentence else R.string.player_forward_10),
                        tint = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun EntryPreview() {
    OwlTheme {
        EntryContent(
            state = EntryUiState(
                loaded = true,
                detail = SampleData.entry(4),
                player = PlayerState(loaded = true, canPlay = true, playing = true, positionMs = 70_000, durationMs = 252_000),
            ),
            snackbar = remember { SnackbarHostState() },
            onBack = {}, onTogglePlay = {}, onSeek = {}, onPrevious = {}, onNext = {}, onCycleSpeed = {}, onSetPreserve = {}, onCopyText = {}, onDelete = {},
            onNamePlace = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EntryDeletedPreview() {
    val base = SampleData.entry(4)!!
    OwlTheme {
        EntryContent(
            state = EntryUiState(
                loaded = true,
                detail = base.copy(item = base.item.copy(audioDeleted = true), audioDeleteAtMs = null),
                player = PlayerState(loaded = true, canPlay = false, positionMs = 63_000, durationMs = 252_000),
            ),
            snackbar = remember { SnackbarHostState() },
            onBack = {}, onTogglePlay = {}, onSeek = {}, onPrevious = {}, onNext = {}, onCycleSpeed = {}, onSetPreserve = {}, onCopyText = {}, onDelete = {},
            onNamePlace = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun EntryPendingPreview() {
    OwlTheme {
        EntryContent(
            state = EntryUiState(
                loaded = true,
                detail = SampleData.entry(6),
                player = PlayerState(loaded = true, canPlay = true, durationMs = 360_000),
            ),
            snackbar = remember { SnackbarHostState() },
            onBack = {}, onTogglePlay = {}, onSeek = {}, onPrevious = {}, onNext = {}, onCycleSpeed = {}, onSetPreserve = {}, onCopyText = {}, onDelete = {},
            onNamePlace = {},
        )
    }
}
