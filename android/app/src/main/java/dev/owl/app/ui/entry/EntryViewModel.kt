package dev.owl.app.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owl.app.model.AudioPlayer
import dev.owl.app.model.EntryDetail
import dev.owl.app.model.PlayerState
import dev.owl.app.model.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class EntryUiState(
    /** False until the repository answered once. */
    val loaded: Boolean = false,
    /** Null after loading = the record does not exist (any more). */
    val detail: EntryDetail? = null,
    val player: PlayerState = PlayerState(),
    val deleting: Boolean = false,
)

/**
 * One record (A8). Owns its [AudioPlayer] (one per Entry screen) and releases it in [onCleared].
 * [initialSeekMs] comes from a search hit so the matching sentence is shown first.
 */
class EntryViewModel(
    private val id: Long,
    private val initialSeekMs: Long?,
    private val repository: TimelineRepository,
    private val player: AudioPlayer,
) : ViewModel() {
    private var loadedOnce = false
    private var loadedFiles: List<File>? = null
    private val deleting = MutableStateFlow(false)

    private val detail = repository.entry(id).onEach { d ->
        if (d == null) return@onEach
        if (!loadedOnce || d.audioFiles != loadedFiles) {
            // (Re)load when the files appear (receive finished) or disappears (retention); keep the position.
            val keep = if (loadedOnce) player.state.value.positionMs else initialSeekMs
            player.load(d.audioFiles, d.item.durationMs)
            if (keep != null && keep > 0) player.seekTo(keep)
            loadedOnce = true
            loadedFiles = d.audioFiles
        }
    }.map { LoadedDetail(it) }

    private data class LoadedDetail(val detail: EntryDetail?)

    val state: StateFlow<EntryUiState> = combine(detail, player.state, deleting) { d, p, del ->
        EntryUiState(loaded = true, detail = d.detail, player = p, deleting = del)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EntryUiState())

    private val sentences get() = state.value.detail?.sentences.orEmpty()
    private val duration: Long
        get() = state.value.player.durationMs.takeIf { it > 0 } ?: state.value.detail?.item?.durationMs ?: 0

    fun togglePlay() {
        val p = player.state.value
        if (p.playing) player.pause() else if (p.canPlay) player.play()
    }

    fun seekTo(ms: Long) = player.seekTo(ms.coerceIn(0, duration.coerceAtLeast(0)))

    /** "이전 문장": restart the current sentence, or go one back if we are at its start. ±10 s without text. */
    fun previous() {
        val pos = player.state.value.positionMs
        val s = sentences
        if (s.isEmpty()) return seekTo(pos - 10_000)
        val cur = s.indexOfLast { it.startMs <= pos }
        val target = when {
            cur < 0 -> 0
            pos - s[cur].startMs > 2_000 -> s[cur].startMs
            cur > 0 -> s[cur - 1].startMs
            else -> 0
        }
        seekTo(target)
    }

    /** "다음 문장": start of the next sentence. +10 s without text. */
    fun next() {
        val pos = player.state.value.positionMs
        val s = sentences
        if (s.isEmpty()) return seekTo(pos + 10_000)
        s.firstOrNull { it.startMs > pos }?.let { seekTo(it.startMs) }
    }

    /** 1.0× → 1.5× → 2.0× → 1.0× */
    fun cycleSpeed() {
        val speeds = listOf(1f, 1.5f, 2f)
        val i = speeds.indexOfFirst { kotlin.math.abs(it - player.state.value.speed) < 0.01f }
        player.setSpeed(speeds[(i + 1) % speeds.size])
    }

    fun setPreserve(preserve: Boolean) {
        viewModelScope.launch { repository.setPreserveAudio(id, preserve) }
    }

    suspend fun transcriptText(): String = repository.transcriptText(id)

    fun delete(onDone: () -> Unit) {
        deleting.value = true
        player.pause()
        viewModelScope.launch {
            repository.deleteEntry(id)
            onDone()
        }
    }

    override fun onCleared() {
        player.release()
    }
}
