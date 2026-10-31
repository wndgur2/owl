/* Energy + ZCR voice activity detector and segmenter. See vad.h. */

#include "vad.h"

/*
 * Per sensitivity (0 = least, 3 = most sensitive):
 *   ratio: how far above the noise floor a frame must be (x8, x5, x3, x2)
 *   min:   absolute mean-square floor (RMS 40, 25, 15, 8 of 32767)
 * The absolute values are first guesses: tune them with the RMS numbers the M0
 * bring-up prints for a quiet room vs. talking (firmware/README.md).
 */
static const uint8_t ratio_q4[4] = {128, 80, 48, 32};
static const uint32_t min_energy[4] = {1600, 625, 225, 64};

void owl_vad_set_sensitivity(struct owl_vad *v, uint8_t sensitivity)
{
	if (sensitivity > 3) {
		sensitivity = 3;
	}
	v->ratio_q4 = ratio_q4[sensitivity];
	v->min_energy = min_energy[sensitivity];
}

void owl_vad_init(struct owl_vad *v, uint8_t sensitivity)
{
	v->floor = 0;
	v->primed = false;
	v->last_energy = 0;
	v->last_zcr = 0;
	owl_vad_set_sensitivity(v, sensitivity);
}

bool owl_vad_frame(struct owl_vad *v, const int16_t *pcm, int n)
{
	if (n <= 0) {
		return false;
	}

	/* Remove DC first: a small offset from the mic would otherwise hide zero crossings. */
	int32_t sum = 0;

	for (int i = 0; i < n; i++) {
		sum += pcm[i];
	}
	const int32_t mean = sum / n;

	uint64_t sq = 0;
	uint16_t zcr = 0;
	bool prev_neg = (pcm[0] - mean) < 0;

	for (int i = 0; i < n; i++) {
		int32_t s = pcm[i] - mean;
		bool neg = s < 0;

		sq += (uint64_t)((int64_t)s * s);
		zcr += (neg != prev_neg);
		prev_neg = neg;
	}

	uint32_t e = (uint32_t)(sq / (uint64_t)n);

	/* zcr is reported per 320-sample (20 ms) frame regardless of n. */
	if (n != 320) {
		zcr = (uint16_t)((uint32_t)zcr * 320U / (uint32_t)n);
	}
	v->last_energy = e;
	v->last_zcr = zcr;

	if (!v->primed) {
		v->floor = e > 0 ? e : 1;
		v->primed = true;
	}

	const uint64_t thr = ((uint64_t)v->floor * v->ratio_q4) / 16U;
	bool above = e > v->min_energy && (uint64_t)e > thr;
	bool loud = (uint64_t)e > thr * 4U;
	bool speech = above && (zcr < OWL_VAD_ZCR_NOISE || loud);

	/* Floor: fast down (1/4 of the gap per frame), slow up (~0.8 % per frame, x1.5 per s). */
	if (e < v->floor) {
		v->floor -= (v->floor - e) / 4U;
	} else {
		uint32_t up = v->floor / 128U + 1U;

		v->floor = (e - v->floor < up) ? e : v->floor + up;
	}
	if (v->floor == 0) {
		v->floor = 1;
	}
	return speech;
}

void owl_segmenter_init(struct owl_segmenter *s, uint32_t hangover_ms, uint32_t max_len_ms)
{
	owl_segmenter_reset(s);
	s->hangover_frames = (uint16_t)(hangover_ms / OWL_VAD_FRAME_MS);
	if (s->hangover_frames == 0) {
		s->hangover_frames = 1;
	}
	owl_segmenter_set_max(s, max_len_ms);
}

void owl_segmenter_set_max(struct owl_segmenter *s, uint32_t max_len_ms)
{
	s->max_frames = max_len_ms / OWL_VAD_FRAME_MS;
	if (s->max_frames == 0) {
		s->max_frames = 1;
	}
}

void owl_segmenter_reset(struct owl_segmenter *s)
{
	s->active = false;
	s->speech_run = 0;
	s->silence_run = 0;
	s->frames = 0;
}

enum owl_seg_action owl_segmenter_step(struct owl_segmenter *s, bool speech)
{
	if (!s->active) {
		s->speech_run = speech ? s->speech_run + 1 : 0;
		if (s->speech_run >= OWL_VAD_ONSET_FRAMES) {
			s->active = true;
			s->frames = 1;
			s->silence_run = 0;
			return OWL_SEG_START;
		}
		return OWL_SEG_IDLE;
	}

	s->silence_run = speech ? 0 : s->silence_run + 1;
	if (s->silence_run >= s->hangover_frames) {
		owl_segmenter_reset(s);
		return OWL_SEG_END;
	}
	if (s->frames >= s->max_frames) {
		s->frames = 1;
		return OWL_SEG_ROLLOVER;
	}
	s->frames++;
	return OWL_SEG_CONTINUE;
}
