package dev.owl.app.device.fake

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Debug builds: real, decodable 16 kHz mono Opus packets for the fake pendant, made with
 * Android's built-in Opus encoder. The sound is a synthetic "voice-like" buzz (a gliding pitch
 * with harmonics, chopped into syllables), so the player has something to play.
 * Assumes the encoder's default 20 ms frames. Falls back to [RandomFrameSource] if the device has no Opus encoder.
 */
class OpusToneSource(private val bitrate: Int = 16_000) : FrameSource {
    private val fallback = RandomFrameSource()

    /**
     * Software encoders on emulators run near real time, so encode a few seconds once and loop
     * those packets (20 ms each) for any length.
     */
    private val clip: List<ByteArray>? by lazy {
        try {
            encode(synth(CLIP_MS))
        } catch (e: Exception) {
            Log.w("OwlFake", "Opus encoder unavailable, using random frames", e)
            null
        }
    }

    override fun frames(durationMs: Long, frameMs: Int): List<ByteArray> {
        val packets = clip?.takeIf { it.isNotEmpty() } ?: return fallback.frames(durationMs, frameMs)
        return List((durationMs / frameMs).toInt()) { packets[it % packets.size] }
    }

    private fun synth(durationMs: Long): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val f0 = 140 + 40 * sin(2 * PI * 0.3 * t) + 20 * sin(2 * PI * 2.1 * t)
            phase += 2 * PI * f0 / SAMPLE_RATE
            var v = 0.0
            for (k in 1..8) v += sin(k * phase) / k
            // ~4 syllables/s, with a short pause every ~3 s like phrases.
            val syllable = max(0.0, sin(2 * PI * 4.0 * t))
            val phrase = if ((t % 3.2) > 2.7) 0.0 else 1.0
            out[i] = (v * 0.18 * syllable * phrase * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun encode(pcm: ShortArray): List<ByteArray> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("no Opus encoder")
        val codec = MediaCodec.createByCodecName(name)
        val packets = ArrayList<ByteArray>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = ByteArray(pcm.size * 2)
            pcm.forEachIndexed { i, s -> bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
            var inPos = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                check(System.currentTimeMillis() < deadline) { "Opus encoder did not finish (${packets.size} packets, in $inPos/${bytes.size})" }
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val len = minOf(buf.capacity(), bytes.size - inPos)
                        buf.clear(); buf.put(bytes, inPos, len)
                        val ptsUs = inPos / 2 * 1_000_000L / SAMPLE_RATE
                        inPos += len
                        inputDone = inPos >= bytes.size
                        codec.queueInputBuffer(idx, 0, len, ptsUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(out)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        packets += ByteArray(info.size).also { buf.get(it) }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        Log.i("OwlFake", "encoded ${pcm.size / 16} ms into ${packets.size} Opus packets with $name")
        return packets
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CLIP_MS = 6_400L
    }
}
