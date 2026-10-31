/* VAD and segmenter on synthetic signals. */

#include <zephyr/ztest.h>

#include "vad.h"

#define N 320 /* 20 ms at 16 kHz */

static int16_t frame[N];
static uint32_t lcg = 12345;

static int16_t noise(int amp)
{
	lcg = lcg * 1103515245u + 12345u;
	return (int16_t)(((int32_t)((lcg >> 16) & 0x7fff) - 16384) * amp / 16384);
}

static void fill_noise(int amp)
{
	for (int i = 0; i < N; i++) {
		frame[i] = noise(amp);
	}
}

/* Sine by recurrence (no libm): y[n] = 2cos(w) y[n-1] - y[n-2]. */
static float s1, s2, k2cos;

static void tone_init(float two_cos_w, float amp, float sin_w)
{
	k2cos = two_cos_w;
	s1 = 0;
	s2 = -amp * sin_w;
}

static void fill_tone(int noise_amp)
{
	for (int i = 0; i < N; i++) {
		float y = k2cos * s1 - s2;

		s2 = s1;
		s1 = y;
		frame[i] = (int16_t)(y + noise(noise_amp));
	}
}

/* 300 Hz at 16 kHz: w = 0.11781, 2cos(w) = 1.986136, sin(w) = 0.117537 */
#define TONE_300 1.986136f, 0.117537f

static int count_speech(struct owl_vad *v, int frames, bool tone, int amp, int noise_amp)
{
	int n = 0;

	for (int f = 0; f < frames; f++) {
		if (tone) {
			fill_tone(noise_amp);
		} else {
			fill_noise(amp);
		}
		n += owl_vad_frame(v, frame, N);
	}
	return n;
}

ZTEST(vad, test_quiet_room_is_silence)
{
	struct owl_vad v;

	owl_vad_init(&v, 2);
	zassert_equal(count_speech(&v, 250, false, 30, 0), 0, "5 s of mic self-noise");
}

ZTEST(vad, test_voice_after_quiet_is_speech)
{
	struct owl_vad v;

	owl_vad_init(&v, 2);
	count_speech(&v, 100, false, 30, 0);
	tone_init(1.986136f * 1.0f, 3000.0f, 0.117537f);
	int n = count_speech(&v, 50, true, 0, 30);

	zassert_true(n >= 45, "voiced 300 Hz at -21 dBFS detected (%d/50)", n);
	zassert_true(v.last_zcr < OWL_VAD_ZCR_NOISE, "low ZCR for voiced sound (%u)", v.last_zcr);
}

ZTEST(vad, test_hiss_is_not_speech)
{
	struct owl_vad v;

	owl_vad_init(&v, 2);
	count_speech(&v, 100, false, 30, 0);
	/* White noise ~2.3x louder in amplitude (~5x energy): above the ratio, but noise-like. */
	int n = count_speech(&v, 50, false, 70, 0);

	zassert_equal(n, 0, "broadband hiss with high ZCR rejected (%d)", n);
	zassert_true(v.last_zcr > OWL_VAD_ZCR_NOISE);
}

ZTEST(vad, test_steady_hum_adapts_away)
{
	struct owl_vad v;

	owl_vad_init(&v, 2);
	count_speech(&v, 100, false, 30, 0);
	/* A fan-like low-frequency hum starts and never stops. */
	tone_init(1.986136f, 600.0f, 0.117537f);
	int first = count_speech(&v, 25, true, 0, 0);
	int later;

	count_speech(&v, 750, true, 0, 0); /* 15 s */
	later = count_speech(&v, 50, true, 0, 0);
	zassert_true(first > 0, "onset of a new sound is noticed");
	/* Floor rises ~x1.5 per second: a +27 dB hum is absorbed in ~14 s. */
	zassert_equal(later, 0, "after 15 s the hum is the new noise floor (%d)", later);
}

ZTEST(vad, test_sensitivity_orders_thresholds)
{
	int hits[4];

	for (int s = 0; s <= 3; s++) {
		struct owl_vad v;

		owl_vad_init(&v, s);
		lcg = 1;
		count_speech(&v, 100, false, 30, 0);
		/* Quiet voice: ~3.5x the noise floor in energy (~5.5 dB). */
		tone_init(1.986136f, 45.0f, 0.117537f);
		hits[s] = count_speech(&v, 25, true, 0, 30);
	}
	zassert_equal(hits[0], 0, "least sensitive (x8) ignores quiet voice");
	zassert_equal(hits[1], 0, "x5 too");
	zassert_true(hits[2] > 15, "default (x3) hears it (%d)", hits[2]);
	zassert_true(hits[3] > 15, "most sensitive hears it (%d)", hits[3]);
	zassert_true(hits[0] <= hits[1] && hits[1] <= hits[2] && hits[2] <= hits[3]);
}

ZTEST(vad, test_segmenter_onset_hangover)
{
	struct owl_segmenter s;

	owl_segmenter_init(&s, 1000, 300000); /* 50-frame hangover */

	zassert_equal(owl_segmenter_step(&s, true), OWL_SEG_IDLE);
	zassert_equal(owl_segmenter_step(&s, false), OWL_SEG_IDLE, "a click is not speech");
	zassert_equal(owl_segmenter_step(&s, true), OWL_SEG_IDLE);
	zassert_equal(owl_segmenter_step(&s, true), OWL_SEG_IDLE);
	zassert_equal(owl_segmenter_step(&s, true), OWL_SEG_START, "3 frames = 60 ms");

	/* A 0.9 s pause stays inside the segment. */
	for (int i = 0; i < 45; i++) {
		zassert_equal(owl_segmenter_step(&s, false), OWL_SEG_CONTINUE);
	}
	zassert_equal(owl_segmenter_step(&s, true), OWL_SEG_CONTINUE);

	/* 1 s of silence ends it. */
	for (int i = 0; i < 49; i++) {
		zassert_equal(owl_segmenter_step(&s, false), OWL_SEG_CONTINUE);
	}
	zassert_equal(owl_segmenter_step(&s, false), OWL_SEG_END);
	zassert_equal(owl_segmenter_step(&s, false), OWL_SEG_IDLE);
}

ZTEST(vad, test_segmenter_max_length_rollover)
{
	struct owl_segmenter s;
	int rollovers = 0, frames = 0;

	owl_segmenter_init(&s, 1500, 1000); /* 50-frame segments */
	for (int i = 0; i < 3; i++) {
		owl_segmenter_step(&s, true);
	}
	zassert_true(s.active);
	for (int i = 0; i < 160; i++) {
		enum owl_seg_action a = owl_segmenter_step(&s, true);

		frames++;
		if (a == OWL_SEG_ROLLOVER) {
			rollovers++;
			zassert_equal(frames, rollovers == 1 ? 50 : 50, "every 50 frames");
			frames = 0;
		} else {
			zassert_equal(a, OWL_SEG_CONTINUE);
		}
	}
	zassert_equal(rollovers, 3, "1 + 160 frames of speech -> three rollovers");
}

ZTEST_SUITE(vad, NULL, NULL, NULL, NULL, NULL);
