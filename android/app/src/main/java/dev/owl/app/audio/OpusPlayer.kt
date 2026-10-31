package dev.owl.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import dev.owl.app.model.AudioPlayer
import dev.owl.app.model.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * A8: plays one raw Owl segment file (32-byte header + `[len u16][Opus packet]` frames).
 *
 * Pipeline: Opus packets → MediaCodec (Android's hardware/software codec API, here the built-in
 * Opus decoder) → 16-bit PCM → AudioTrack (a raw PCM output stream to the speaker).
 *
 * Threading: public methods return immediately and are applied in call order by one command
 * coroutine. Decoding runs in its own job on [io]; pause/seek cancel that job (which frees the
 * codec and track) and start a new one at the new position. A restart costs a few ms plus
 * [OpusFormat.SEEK_PREROLL_MS] of decoding that is thrown away, which is simpler and safer than
 * flushing a running codec.
 *
 * Not handled (v1): audio focus / ducking for other apps, and headphone-unplug pause.
 */
class OpusPlayer(private val io: CoroutineDispatcher = Dispatchers.IO) : AudioPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    // Only touched inside commands (one at a time).
    private var segment: OpusSegment? = null
    private var playJob: Job? = null

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "player command failed", e)
                }
            }
        }
    }

    private fun command(block: suspend () -> Unit) {
        commands.trySend(block)
    }

    override fun load(file: File?, durationMs: Long) = command {
        stopPlayback()
        segment = null
        _state.value = PlayerState(loaded = true, canPlay = false, durationMs = durationMs, speed = _state.value.speed)
        if (file == null) return@command // audio deleted: sentence navigation only (A8)

        val parsed = withContext(io) {
            try {
                OpusSegment.parse(file.readBytes())
            } catch (e: Exception) {
                Log.w(TAG, "cannot read segment ${file.name}", e)
                null
            }
        } ?: return@command
        segment = parsed
        _state.update {
            it.copy(
                canPlay = parsed.frames.isNotEmpty(),
                // Frame count × frame_ms is exact; the caller's value may be an estimate.
                durationMs = if (parsed.durationMs > 0) parsed.durationMs else durationMs,
            )
        }
    }

    override fun play() = command {
        val seg = segment ?: return@command
        val s = _state.value
        if (!s.canPlay || playJob?.isActive == true) return@command
        startPlayback(seg, if (s.positionMs >= s.durationMs) 0 else s.positionMs)
    }

    override fun pause() = command { stopPlayback() }

    override fun seekTo(positionMs: Long) = command {
        val target = positionMs.coerceIn(0, _state.value.durationMs)
        val seg = segment
        val wasPlaying = playJob?.isActive == true
        stopPlayback(keepPlayingFlag = wasPlaying)
        _state.update { it.copy(positionMs = target) }
        if (wasPlaying && seg != null) startPlayback(seg, target)
    }

    /** Playback speed via AudioTrack PlaybackParams (pitch is kept). Not in [AudioPlayer] yet. */
    override fun setSpeed(speed: Float) = command {
        val clamped = speed.coerceIn(0.5f, 2f)
        _state.update { it.copy(speed = clamped) }
        val seg = segment
        if (playJob?.isActive == true && seg != null) {
            stopPlayback(keepPlayingFlag = true)
            startPlayback(seg, _state.value.positionMs)
        }
    }

    override fun release() {
        commands.close()
        scope.cancel() // the decode job's finally block frees codec and track
    }

    private fun startPlayback(seg: OpusSegment, fromMs: Long) {
        _state.update { it.copy(playing = true, positionMs = fromMs) }
        val speed = _state.value.speed
        playJob = scope.launch(io) { decodeAndPlay(seg, fromMs, speed) }
    }

    private suspend fun stopPlayback(keepPlayingFlag: Boolean = false) {
        playJob?.cancelAndJoin()
        playJob = null
        if (!keepPlayingFlag) _state.update { it.copy(playing = false) }
    }

    // --- decode loop (runs on [io]) -------------------------------------------------------

    private suspend fun decodeAndPlay(seg: OpusSegment, fromMs: Long, speed: Float) {
        val frames = seg.frames
        val frameMs = seg.frameMs
        val targetFrame = OpusFormat.frameIndexAt(fromMs, frameMs, frames.size)
        val targetUs = OpusFormat.frameTimeUs(targetFrame, frameMs)
        var nextFrame = OpusFormat.decodeStartFrame(targetFrame, frameMs)

        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var outRate = OpusFormat.DECODE_RATE
        var outChannels = seg.header.channels
        var writtenPcmFrames = 0L
        var lastUpdateMs = 0L
        var finished = false

        fun publishPosition(force: Boolean = false) {
            val t = track ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastUpdateMs < POSITION_UPDATE_MS) return
            lastUpdateMs = now
            val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val pos = OpusFormat.positionMs(targetFrame, frameMs, played, outRate)
            _state.update { it.copy(positionMs = pos.coerceAtMost(it.durationMs)) }
        }

        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                configure(decoderFormat(seg), null, null, 0)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                currentCoroutineContext().ensureActive()

                // Feed one packet if the decoder has a free input buffer.
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: error("no input buffer")
                        buf.clear()
                        if (nextFrame < frames.size) {
                            val packet = frames[nextFrame]
                            buf.put(packet)
                            codec.queueInputBuffer(inIndex, 0, packet.size, OpusFormat.frameTimeUs(nextFrame, frameMs), 0)
                            nextFrame++
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, OpusFormat.frameTimeUs(nextFrame, frameMs), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                // Take decoded PCM, if any.
                val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIndex >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // Pre-roll output (before the seek target) only primed the decoder: drop it.
                    if (info.size > 0 && info.presentationTimeUs >= targetUs) {
                        val t = track ?: createTrack(outRate, outChannels, speed).also { track = it; it.play() }
                        val pcm = codec.getOutputBuffer(outIndex) ?: error("no output buffer")
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        writeFully(t, pcm) { publishPosition() }
                        writtenPcmFrames += info.size / (BYTES_PER_SAMPLE * outChannels)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    publishPosition()
                    if (eos) break
                }
            }

            // Input is done; wait until the track has actually played what we wrote.
            val t = track
            if (t != null) {
                val drainDeadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
                while ((t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < writtenPcmFrames &&
                    System.currentTimeMillis() < drainDeadline
                ) {
                    publishPosition()
                    delay(POLL_MS)
                }
            }
            finished = true
            _state.update { it.copy(playing = false, positionMs = it.durationMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // MediaCodec.CodecException, AudioTrack errors, ...: stop, keep the player usable.
            Log.w(TAG, "playback failed", e)
            _state.update { it.copy(playing = false) }
        } finally {
            if (!finished) publishPosition(force = true) // pause/seek: remember where we were
            track?.let { t ->
                runCatching { t.pause(); t.flush() }
                t.release()
            }
            codec?.let { c ->
                runCatching { c.stop() }
                c.release()
            }
        }
    }

    private fun decoderFormat(seg: OpusSegment): MediaFormat {
        val ch = seg.header.channels
        // 48 kHz: what Opus decoders run at (Android's C2 Opus decoder only accepts 48000 here).
        // The segment's real input rate (16 kHz) goes into OpusHead for information only.
        return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OpusFormat.DECODE_RATE, ch).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(OpusFormat.opusHead(ch, seg.header.sampleRate)))
            setByteBuffer("csd-1", ByteBuffer.wrap(OpusFormat.csd1()))
            setByteBuffer("csd-2", ByteBuffer.wrap(OpusFormat.csd2()))
        }
    }

    private fun createTrack(sampleRate: Int, channels: Int, speed: Float): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // ~250 ms of buffer: smooth playback, while pause/seek still feel instant (we flush).
        val buffer = maxOf(minBuffer, sampleRate * channels * BYTES_PER_SAMPLE / 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(buffer)
            .build()
        if (speed != 1f) {
            try {
                track.playbackParams = track.playbackParams.setSpeed(speed)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "speed $speed not supported", e)
            }
        }
        return track
    }

    /**
     * Non-blocking writes with short suspending waits, so a pause/seek (job cancellation) takes
     * effect within [POLL_MS] instead of after a blocking write returns.
     */
    private suspend fun writeFully(track: AudioTrack, pcm: ByteBuffer, onWait: () -> Unit) {
        while (pcm.hasRemaining()) {
            val n = track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) error("AudioTrack.write returned $n")
            if (n == 0) {
                onWait()
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val TAG = "OwlOpusPlayer"
        const val BYTES_PER_SAMPLE = 2
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val POSITION_UPDATE_MS = 50L
        const val POLL_MS = 10L
        const val DRAIN_TIMEOUT_MS = 2_000L
    }
}
