/*
 * Recorder: capture, VAD, encode, and hand frames to the flash writer. See audio.h.
 *
 * PDM in one paragraph: the MEMS mic outputs a 1-bit stream at ~1-3 MHz; the
 * nRF PDM peripheral low-pass filters and decimates it to 16 kHz PCM16 and DMAs
 * it into blocks from a memory slab. dmic_read() hands us one filled block
 * (20 ms = 320 samples here); we must give it back with k_mem_slab_free().
 * If we are too slow the driver runs out of free blocks and drops audio.
 */

#include <errno.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/audio/dmic.h>
#include <zephyr/drivers/regulator.h>
#include <zephyr/sys/ring_buffer.h>
#include <zephyr/logging/log.h>

#if defined(CONFIG_OWL_CODEC_OPUS)
#include <opus.h>
#endif

#include "audio.h"
#include "owl_proto.h"
#include "storage.h"
#include "vad.h"

LOG_MODULE_REGISTER(audio, CONFIG_OWL_LOG_LEVEL);

#define RATE          16000
#define FRAME_MS      20
#define FRAME_SAMPLES (RATE * FRAME_MS / 1000) /* 320 */
#define FRAME_BYTES   (FRAME_SAMPLES * 2)
/* 12 blocks = 240 ms of slack for encoder bursts (pre-roll) and scheduling. */
#define BLOCK_COUNT   12
#define PREROLL_FRAMES (CONFIG_OWL_VAD_PREROLL_MS / FRAME_MS)

#if defined(CONFIG_OWL_CODEC_OPUS)
#define CODEC        OWL_CODEC_OPUS
#define MAX_PACKET   256 /* 16 kbps CVBR gives ~40 B per 20 ms; this is generous */
#else
#define CODEC        OWL_CODEC_PCM16
#define MAX_PACKET   FRAME_BYTES
#endif

static const struct device *const dmic = DEVICE_DT_GET(DT_ALIAS(dmic0));

/* The board switches mic + IMU power with a load switch; off while not recording. */
#if DT_NODE_EXISTS(DT_NODELABEL(pdm_imu_pwr))
static const struct device *const mic_pwr = DEVICE_DT_GET(DT_NODELABEL(pdm_imu_pwr));
#else
static const struct device *const mic_pwr;
#endif

K_MEM_SLAB_DEFINE_STATIC(pcm_slab, FRAME_BYTES, BLOCK_COUNT, 4);

static audio_state_cb_t state_cb;
static K_SEM_DEFINE(wake, 0, 1);
static atomic_t enabled;
static atomic_t shutting_down;
static atomic_t capturing;
static atomic_t full;

/* Config, applied by the audio thread at safe points. */
static struct k_spinlock cfg_lock;
static struct owl_config cfg = {.opus_bitrate = 16000, .vad_sensitivity = 2, .segment_max_s = 300};
static atomic_t cfg_dirty;

/* -------------------------------------------------- ring to the writer */

enum msg_type { MSG_BEGIN = 1, MSG_FRAME = 2, MSG_END = 3 };

RING_BUF_DECLARE(ring, CONFIG_OWL_REC_RING_SIZE);
static struct k_spinlock ring_lock;
static K_SEM_DEFINE(ring_sem, 0, 1);
static atomic_t writer_busy;

/* Puts [type][len u16][a][b] atomically, or nothing. */
static bool post(uint8_t type, const void *a, uint16_t alen, const void *b, uint16_t blen)
{
	uint8_t hdr[3] = {type, (uint8_t)(alen + blen), (uint8_t)((alen + blen) >> 8)};
	k_spinlock_key_t key = k_spin_lock(&ring_lock);
	bool ok = ring_buf_space_get(&ring) >= sizeof(hdr) + alen + blen;

	if (ok) {
		ring_buf_put(&ring, hdr, sizeof(hdr));
		if (alen) {
			ring_buf_put(&ring, a, alen);
		}
		if (blen) {
			ring_buf_put(&ring, b, blen);
		}
	}
	k_spin_unlock(&ring_lock, key);
	if (ok) {
		k_sem_give(&ring_sem);
	}
	return ok;
}

static bool post_begin(uint64_t start_ms)
{
	uint8_t b[8];

	owl_put_u64(b, start_ms);
	return post(MSG_BEGIN, b, sizeof(b), NULL, 0);
}

static bool ring_get(void *dst, uint32_t len)
{
	k_spinlock_key_t key = k_spin_lock(&ring_lock);
	uint32_t n = ring_buf_get(&ring, dst, len);

	k_spin_unlock(&ring_lock, key);
	return n == len;
}

static bool ring_empty(void)
{
	k_spinlock_key_t key = k_spin_lock(&ring_lock);
	bool e = ring_buf_is_empty(&ring);

	k_spin_unlock(&ring_lock, key);
	return e;
}

static void set_full(bool on)
{
	if (atomic_set(&full, on) == on) {
		return;
	}
	if (on) {
		/* Event 4 once per episode; recording resumes after the phone frees space. */
		storage_event(OWL_EVT_STORAGE_FULL, 0);
	}
	if (state_cb) {
		state_cb();
	}
	k_sem_give(&wake);
}

static void writer_thread(void *a, void *b, void *c)
{
	static uint8_t payload[MAX_PACKET + 8];
	bool seg_open = false;
	bool drop = false;

	for (;;) {
		if (ring_empty()) {
			atomic_set(&writer_busy, 0);
			k_sem_take(&ring_sem, K_FOREVER);
			continue;
		}
		/* Set before taking the message, so audio_shutdown() never sees "empty and idle"
		 * while the last message is still being written.
		 */
		atomic_set(&writer_busy, 1);

		uint8_t hdr[3];

		if (!ring_get(hdr, sizeof(hdr))) {
			continue;
		}
		uint16_t len = owl_get_u16(&hdr[1]);

		if (len > sizeof(payload) || !ring_get(payload, len)) {
			LOG_ERR("ring corrupt, resetting");
			ring_buf_reset(&ring);
			continue;
		}

		int err = 0;

		switch (hdr[0]) {
		case MSG_BEGIN:
			if (seg_open) {
				storage_seg_end(); /* its END was lost */
			}
			err = storage_seg_begin(CODEC, FRAME_MS, owl_get_u64(payload));
			seg_open = err == 0;
			drop = !seg_open;
			break;
		case MSG_FRAME:
			if (seg_open && !drop) {
				err = storage_seg_append(payload, len);
			}
			break;
		case MSG_END:
			if (seg_open) {
				storage_seg_end();
			}
			seg_open = false;
			drop = false;
			break;
		}

		if (err) {
			/* Keep what is on flash and skip the rest of this segment. */
			if (seg_open) {
				storage_seg_end();
				seg_open = false;
			}
			drop = true;
			if (err == -ENOSPC) {
				set_full(true);
			} else {
				LOG_ERR("writer: msg %u err %d", hdr[0], err);
			}
		}
	}
}

K_THREAD_DEFINE(writer_tid, 4096, writer_thread, NULL, NULL, NULL, 6, 0, -1);

/* ------------------------------------------------------------- encoder */

#if defined(CONFIG_OWL_CODEC_OPUS)
static uint8_t enc_mem[CONFIG_OWL_OPUS_ENC_MEM] __aligned(8);
static OpusEncoder *enc;

static int codec_init(uint32_t bitrate)
{
	int size = opus_encoder_get_size(1);

	if (size <= 0 || size > (int)sizeof(enc_mem)) {
		LOG_ERR("opus encoder needs %d bytes, have %u", size, sizeof(enc_mem));
		return -ENOMEM;
	}
	enc = (OpusEncoder *)enc_mem;
	int err = opus_encoder_init(enc, RATE, 1, OPUS_APPLICATION_VOIP);

	if (err != OPUS_OK) {
		LOG_ERR("opus init: %d", err);
		return -EIO;
	}
	opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
	opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(CONFIG_OWL_OPUS_COMPLEXITY));
	opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
	opus_encoder_ctl(enc, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND));
	/* Constrained VBR: size stays near the target, which keeps the 2 h estimate honest. */
	opus_encoder_ctl(enc, OPUS_SET_VBR(1));
	opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(1));
	/* VAD already removed silence; DTX/FEC only matter for live streaming. */
	opus_encoder_ctl(enc, OPUS_SET_DTX(0));
	opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
	opus_encoder_ctl(enc, OPUS_SET_LSB_DEPTH(16));
	LOG_INF("opus: %d B state, %u bps, complexity %d", size, bitrate,
		CONFIG_OWL_OPUS_COMPLEXITY);
	return 0;
}

static void codec_set_bitrate(uint32_t bitrate)
{
	opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
}

/* Each segment must decode on its own, so the encoder forgets the previous one. */
static void codec_reset(void)
{
	opus_encoder_ctl(enc, OPUS_RESET_STATE);
}

static int codec_encode(const int16_t *pcm, uint8_t *out)
{
	return opus_encode(enc, pcm, FRAME_SAMPLES, out, MAX_PACKET);
}
#else
static int codec_init(uint32_t bitrate)
{
	ARG_UNUSED(bitrate);
	LOG_WRN("PCM16 debug codec: ~115 MB/h, flash fills in ~8 min");
	return 0;
}

static void codec_set_bitrate(uint32_t bitrate)
{
	ARG_UNUSED(bitrate);
}

static void codec_reset(void)
{
}

static int codec_encode(const int16_t *pcm, uint8_t *out)
{
	memcpy(out, pcm, FRAME_BYTES); /* nRF is little-endian, same as the wire */
	return FRAME_BYTES;
}
#endif

/* ---------------------------------------------------------- audio thread */

static int16_t preroll[PREROLL_FRAMES][FRAME_SAMPLES];
static int preroll_head, preroll_count;
static uint8_t packet[MAX_PACKET];
static bool broken; /* a frame was lost to a full ring: restart the segment */

static struct {
	uint32_t frames;
	uint32_t cycles_sum;
	uint32_t cycles_max;
	uint32_t dropped;
} stats;

static void encode_and_post(const int16_t *pcm)
{
	uint32_t t0 = k_cycle_get_32();
	int n = codec_encode(pcm, packet);
	uint32_t dt = k_cycle_get_32() - t0;

	stats.frames++;
	stats.cycles_sum += dt;
	stats.cycles_max = MAX(stats.cycles_max, dt);
	if (stats.frames == 500) { /* every 10 s of speech */
		LOG_INF("encode: avg %u us, max %u us per 20 ms frame, %u dropped",
			k_cyc_to_us_floor32(stats.cycles_sum / stats.frames),
			k_cyc_to_us_floor32(stats.cycles_max), stats.dropped);
		memset(&stats, 0, sizeof(stats));
	}

	if (n <= 0) {
		LOG_ERR("encode: %d", n);
		return;
	}
	if (broken) {
		/* Close the damaged segment and start fresh so frame count == duration again. */
		if (!post(MSG_END, NULL, 0, NULL, 0) ||
		    !post_begin((uint64_t)k_uptime_get() - FRAME_MS)) {
			stats.dropped++;
			return;
		}
		broken = false;
		codec_reset();
	}
	if (!post(MSG_FRAME, packet, (uint16_t)n, NULL, 0)) {
		stats.dropped++;
		broken = true;
	}
}

static void preroll_push(const int16_t *pcm)
{
	if (PREROLL_FRAMES == 0) {
		return;
	}
	memcpy(preroll[preroll_head], pcm, FRAME_BYTES);
	preroll_head = (preroll_head + 1) % PREROLL_FRAMES;
	preroll_count = MIN(preroll_count + 1, PREROLL_FRAMES);
}

static void preroll_flush(void)
{
	int start = (preroll_head - preroll_count + PREROLL_FRAMES) % MAX(PREROLL_FRAMES, 1);

	for (int i = 0; i < preroll_count; i++) {
		encode_and_post(preroll[(start + i) % PREROLL_FRAMES]);
	}
	preroll_count = 0;
}

static void apply_gain(int16_t *pcm)
{
#if CONFIG_OWL_MIC_GAIN_SHIFT > 0
	for (int i = 0; i < FRAME_SAMPLES; i++) {
		int32_t v = (int32_t)pcm[i] << CONFIG_OWL_MIC_GAIN_SHIFT;

		pcm[i] = (int16_t)CLAMP(v, INT16_MIN, INT16_MAX);
	}
#else
	ARG_UNUSED(pcm);
#endif
}

static int mic_start(void)
{
	struct pcm_stream_cfg stream = {
		.pcm_rate = RATE,
		.pcm_width = 16,
		.block_size = FRAME_BYTES,
		.mem_slab = &pcm_slab,
	};
	struct dmic_cfg dcfg = {
		.io = {
			.min_pdm_clk_freq = 1000000,
			.max_pdm_clk_freq = 3500000,
			.min_pdm_clk_dc = 40,
			.max_pdm_clk_dc = 60,
		},
		.streams = &stream,
		.channel = {
			.req_num_streams = 1,
			.req_num_chan = 1,
		},
	};
	/* The mic's L/R strap picks the clock edge it drives data on; see Kconfig. */
	dcfg.channel.req_chan_map_lo = dmic_build_channel_map(
		0, 0, IS_ENABLED(CONFIG_OWL_PDM_CHANNEL_RIGHT) ? PDM_CHAN_RIGHT : PDM_CHAN_LEFT);

	static bool configured;
	int err = 0;

	if (mic_pwr && device_is_ready(mic_pwr)) {
		regulator_enable(mic_pwr); /* includes the rail's 5 ms startup delay */
	}
	/* The PDM keeps its configuration across stop/start; set it up once. */
	if (!configured) {
		err = dmic_configure(dmic, &dcfg);
		configured = err == 0;
	}
	if (!err) {
		err = dmic_trigger(dmic, DMIC_TRIGGER_START);
	}
	if (err) {
		LOG_ERR("mic start: %d", err);
		if (mic_pwr && device_is_ready(mic_pwr)) {
			regulator_disable(mic_pwr);
		}
	}
	return err;
}

static void mic_stop(void)
{
	dmic_trigger(dmic, DMIC_TRIGGER_STOP);
	/* STOP is asynchronous: the PDM finishes the block in flight, then the driver
	 * marks itself inactive. Starting again before that would be ignored.
	 */
	k_msleep(2 * FRAME_MS + 10);
	/* Return the filled blocks we never read. */
	void *buf;
	size_t size;

	while (dmic_read(dmic, 0, &buf, &size, 0) == 0) {
		k_mem_slab_free(&pcm_slab, buf);
	}
	if (mic_pwr && device_is_ready(mic_pwr)) {
		regulator_disable(mic_pwr);
	}
}

static bool should_capture(void)
{
	return atomic_get(&enabled) && !atomic_get(&full) && !atomic_get(&shutting_down);
}

static void set_capturing(bool on)
{
	if (atomic_set(&capturing, on) != on && state_cb) {
		state_cb();
	}
}

static void load_cfg(struct owl_segmenter *seg, struct owl_vad *vad, uint32_t *bitrate)
{
	k_spinlock_key_t key = k_spin_lock(&cfg_lock);
	struct owl_config c = cfg;

	k_spin_unlock(&cfg_lock, key);
	owl_vad_set_sensitivity(vad, c.vad_sensitivity);
	owl_segmenter_set_max(seg, (uint32_t)c.segment_max_s * 1000U);
	*bitrate = c.opus_bitrate;
}

static void check_space(void)
{
	struct storage_usage u;

	storage_get_usage(&u);
	set_full(u.full);
}

static void audio_thread(void *a, void *b, void *c)
{
	static struct owl_vad vad;
	static struct owl_segmenter seg;
	uint32_t bitrate = 16000;

	owl_vad_init(&vad, 2);
	owl_segmenter_init(&seg, CONFIG_OWL_VAD_HANGOVER_MS, 300000);
	load_cfg(&seg, &vad, &bitrate);
	if (codec_init(bitrate)) {
		LOG_ERR("no encoder, recorder disabled");
		return;
	}
	k_thread_start(writer_tid);

	for (;;) {
		check_space();
		if (!should_capture()) {
			set_capturing(false);
			/* Re-check now and then: space may be freed without a callback. */
			k_sem_take(&wake, K_SECONDS(30));
			continue;
		}
		if (mic_start()) {
			k_sem_take(&wake, K_SECONDS(5));
			continue;
		}
		set_capturing(true);
		owl_segmenter_reset(&seg);
		preroll_count = 0;
		broken = false;
		uint32_t n = 0;

		while (should_capture()) {
			void *buf;
			size_t size;

			if (atomic_clear(&cfg_dirty)) {
				uint32_t br;

				load_cfg(&seg, &vad, &br);
				if (br != bitrate) {
					bitrate = br;
					codec_set_bitrate(bitrate);
				}
			}

			int err = dmic_read(dmic, 0, &buf, &size, 200);

			if (err) {
				LOG_ERR("mic read: %d, restarting mic", err);
				break;
			}
			int16_t *pcm = buf;

			apply_gain(pcm);
			bool speech = owl_vad_frame(&vad, pcm, (int)(size / 2));
			enum owl_seg_action act = owl_segmenter_step(&seg, speech);
			uint64_t now = (uint64_t)k_uptime_get();

			switch (act) {
			case OWL_SEG_IDLE:
				preroll_push(pcm);
				break;
			case OWL_SEG_START:
				/* Start time = oldest pre-roll frame; capture latency ~1 frame. */
				broken = !post_begin(now - (uint64_t)(preroll_count + 1) * FRAME_MS);
				codec_reset();
				preroll_flush();
				encode_and_post(pcm);
				break;
			case OWL_SEG_CONTINUE:
				encode_and_post(pcm);
				break;
			case OWL_SEG_END:
				encode_and_post(pcm);
				/* If END doesn't fit, the writer closes the segment at the next BEGIN. */
				post(MSG_END, NULL, 0, NULL, 0);
				break;
			case OWL_SEG_ROLLOVER:
				broken = !post(MSG_END, NULL, 0, NULL, 0) ||
					 !post_begin(now - FRAME_MS);
				codec_reset();
				encode_and_post(pcm);
				break;
			}
			k_mem_slab_free(&pcm_slab, buf);

			if (++n % 50 == 0) { /* once a second */
				check_space();
				LOG_DBG("vad e=%u zcr=%u floor=%u", vad.last_energy, vad.last_zcr,
					vad.floor);
			}
		}

		if (seg.active) {
			post(MSG_END, NULL, 0, NULL, 0);
			owl_segmenter_reset(&seg);
		}
		mic_stop();
	}
}

K_THREAD_DEFINE(audio_tid, CONFIG_OWL_AUDIO_STACK_SIZE, audio_thread, NULL, NULL, NULL, 3, 0, -1);

/* ---------------------------------------------------------------- public */

static void space_freed(void)
{
	k_sem_give(&wake);
}

int audio_init(audio_state_cb_t cb)
{
	state_cb = cb;
	storage_set_space_cb(space_freed);
	if (!device_is_ready(dmic)) {
		LOG_ERR("dmic not ready");
		return -ENODEV;
	}
	if (mic_pwr && device_is_ready(mic_pwr)) {
		regulator_disable(mic_pwr); /* boot default is on; we turn it on when recording */
	}
	return 0;
}

void audio_start(void)
{
	k_thread_start(audio_tid);
}

void audio_set_enabled(bool on)
{
	atomic_set(&enabled, on);
	k_sem_give(&wake);
}

void audio_apply_config(const struct owl_config *c)
{
	k_spinlock_key_t key = k_spin_lock(&cfg_lock);

	cfg = *c;
	k_spin_unlock(&cfg_lock, key);
	atomic_set(&cfg_dirty, 1);
}

bool audio_is_capturing(void)
{
	return atomic_get(&capturing);
}

bool audio_is_full(void)
{
	return atomic_get(&full);
}

void audio_shutdown(void)
{
	atomic_set(&shutting_down, 1);
	k_sem_give(&wake);
	/* Wait for the audio thread to post END and stop the mic, then for the writer. */
	for (int i = 0; i < 50 && atomic_get(&capturing); i++) {
		k_msleep(20);
	}
	for (int i = 0; i < 150 && (!ring_empty() || atomic_get(&writer_busy)); i++) {
		k_msleep(20);
	}
	storage_seg_end(); /* no-op if the writer already closed it */
}
