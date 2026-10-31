/* libopus as configured for the pendant: encode 16 kHz voice-like audio, decode it back. */

#include <string.h>
#include <zephyr/ztest.h>
#include <opus.h>

#define RATE    16000
#define FRAME   320 /* 20 ms */
#define FRAMES  150 /* 3 s */

static int16_t pcm[FRAMES * FRAME];
static int16_t out[FRAME];
static uint8_t pkt[256];
static uint8_t enc_mem[32768] __aligned(8);
static uint8_t dec_mem[32768] __aligned(8);

/* Voiced-speech stand-in: 150 Hz + 2 harmonics, syllable-rate (4 Hz) envelope,
 * a little noise. No libm: sines by recurrence.
 */
static void make_signal(void)
{
	static const float k[3] = {1.998266f, 1.993068f, 1.984418f}; /* 2cos(w), 150/300/450 Hz */
	float a[3] = {0, 0, 0}, b[3] = {-0.0589f, -0.1178f, -0.1765f}; /* -sin(w) */
	float env1 = 0, env2 = -0.0015708f; /* 4 Hz envelope */
	uint32_t lcg = 7;

	for (int n = 0; n < FRAMES * FRAME; n++) {
		float y = 0;

		for (int h = 0; h < 3; h++) {
			float v = k[h] * a[h] - b[h];

			b[h] = a[h];
			a[h] = v;
			y += v / (float)(h + 1);
		}
		float e = 1.999994f * env1 - env2;

		env2 = env1;
		env1 = e;
		lcg = lcg * 1103515245u + 12345u;
		float noise = (float)((int32_t)((lcg >> 16) & 0x7fff) - 16384) / 16384.0f;

		pcm[n] = (int16_t)(6000.0f * y * (0.6f + 0.4f * e) + 100.0f * noise);
	}
}

static uint64_t energy(const int16_t *x, int n)
{
	uint64_t s = 0;

	for (int i = 0; i < n; i++) {
		s += (int64_t)x[i] * x[i];
	}
	return s;
}

ZTEST(opus, test_encoder_settings_bitrate_and_roundtrip)
{
	OpusEncoder *enc = (OpusEncoder *)enc_mem;
	OpusDecoder *dec = (OpusDecoder *)dec_mem;
	int esize = opus_encoder_get_size(1);
	int dsize = opus_decoder_get_size(1);
	int err;

	TC_PRINT("opus %s: encoder state %d B, decoder state %d B\n", opus_get_version_string(),
		 esize, dsize);
	zassert_true(esize > 0 && esize <= (int)sizeof(enc_mem),
		     "CONFIG_OWL_OPUS_ENC_MEM (32768) must hold the encoder (%d)", esize);

	/* Same settings as src/audio.c codec_init(). */
	zassert_equal(opus_encoder_init(enc, RATE, 1, OPUS_APPLICATION_VOIP), OPUS_OK);
	opus_encoder_ctl(enc, OPUS_SET_BITRATE(16000));
	opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(3));
	opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
	opus_encoder_ctl(enc, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND));
	opus_encoder_ctl(enc, OPUS_SET_VBR(1));
	opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(1));
	opus_encoder_ctl(enc, OPUS_SET_DTX(0));
	opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
	opus_encoder_ctl(enc, OPUS_SET_LSB_DEPTH(16));
	zassert_equal(opus_decoder_init(dec, RATE, 1), OPUS_OK);

	make_signal();

	uint32_t bytes = 0;
	int maxlen = 0;
	uint64_t ein = 0, eout = 0;

	for (int f = 0; f < FRAMES; f++) {
		int n = opus_encode(enc, &pcm[f * FRAME], FRAME, pkt, sizeof(pkt));

		zassert_true(n > 0, "encode frame %d: %d", f, n);
		bytes += n;
		maxlen = MAX(maxlen, n);

		/* Every packet is a 20 ms wideband frame, so the phone decodes it as such. */
		zassert_equal(opus_packet_get_nb_samples(pkt, n, RATE), FRAME);
		zassert_equal(opus_packet_get_bandwidth(pkt), OPUS_BANDWIDTH_WIDEBAND);

		err = opus_decode(dec, pkt, n, out, FRAME, 0);
		zassert_equal(err, FRAME, "decode frame %d: %d", f, err);
		if (f >= 10) { /* skip codec warm-up */
			ein += energy(&pcm[f * FRAME], FRAME);
			eout += energy(out, FRAME);
		}
	}

	uint32_t bps = bytes * 8U * 1000U / (FRAMES * 20U);

	TC_PRINT("%u bytes for %d ms: %u bps, max packet %d B, out/in energy %u%%\n", bytes,
		 FRAMES * 20, bps, maxlen, (unsigned int)(eout * 100 / ein));
	zassert_true(bps > 12000 && bps < 20000, "~16 kbps (got %u)", bps);
	zassert_true(maxlen < 128, "constrained VBR keeps packets small");
	/* Lossy, so not sample-exact; the decoded level must still match the input. */
	zassert_true(eout * 2 > ein && eout < ein * 2, "decoded level %u%% of input",
		     (unsigned int)(eout * 100 / ein));

	/* OPUS_RESET_STATE (done at every segment start) keeps working. */
	zassert_equal(opus_encoder_ctl(enc, OPUS_RESET_STATE), OPUS_OK);
	zassert_true(opus_encode(enc, pcm, FRAME, pkt, sizeof(pkt)) > 0);
}

ZTEST_SUITE(opus, NULL, NULL, NULL, NULL, NULL);
