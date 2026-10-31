/*
 * Voice activity detection + segmenter (PRD D1, D2). Pure logic, integer only.
 *
 * Per 20 ms frame (320 samples at 16 kHz):
 *   - energy: mean square of the samples
 *   - zero-crossing count: voiced speech crosses zero far less often than hiss
 *   - noise floor: follows quiet frames quickly and drifts up slowly, so a fan
 *     that switches on stops counting as speech after a few seconds
 * A frame is "speech" when its energy is a sensitivity-dependent factor above the
 * noise floor (and above an absolute minimum), and its ZCR is not noise-like
 * (unless it is very loud).
 *
 * The segmenter turns frame decisions into segment boundaries:
 *   - ONSET_FRAMES speech frames in a row start a segment; the caller also
 *     stores the pre-roll it kept, so the first syllable is not clipped
 *   - HANGOVER of silence ends it (short pauses stay in one segment)
 *   - max length forces a rollover (end + immediate new start)
 */

#ifndef OWL_VAD_H_
#define OWL_VAD_H_

#include <stdbool.h>
#include <stdint.h>

#define OWL_VAD_FRAME_MS      20
#define OWL_VAD_ONSET_FRAMES  3  /* 60 ms of speech to start */
/* Noise-like ZCR: above ~3.2 kHz average crossing rate. */
#define OWL_VAD_ZCR_NOISE     80

struct owl_vad {
	uint32_t floor;      /* noise floor, mean square units */
	uint32_t min_energy; /* absolute floor, below which nothing is speech */
	uint8_t ratio_q4;    /* energy must exceed floor * ratio_q4 / 16 */
	bool primed;
	/* last frame, for logging / tests */
	uint32_t last_energy;
	uint16_t last_zcr;
};

void owl_vad_init(struct owl_vad *v, uint8_t sensitivity);
void owl_vad_set_sensitivity(struct owl_vad *v, uint8_t sensitivity);
bool owl_vad_frame(struct owl_vad *v, const int16_t *pcm, int n);

enum owl_seg_action {
	OWL_SEG_IDLE,     /* not in a segment, drop the frame (keep it as pre-roll) */
	OWL_SEG_START,    /* begin a segment: write pre-roll, then this frame */
	OWL_SEG_CONTINUE, /* append this frame */
	OWL_SEG_END,      /* append this frame, then close the segment */
	OWL_SEG_ROLLOVER, /* close the segment, start a new one with this frame */
};

struct owl_segmenter {
	bool active;
	uint16_t speech_run;
	uint16_t silence_run;
	uint32_t frames;        /* in the current segment */
	uint16_t hangover_frames;
	uint32_t max_frames;
};

void owl_segmenter_init(struct owl_segmenter *s, uint32_t hangover_ms, uint32_t max_len_ms);
void owl_segmenter_set_max(struct owl_segmenter *s, uint32_t max_len_ms);
enum owl_seg_action owl_segmenter_step(struct owl_segmenter *s, bool speech);
/* Forget an open segment (e.g. recording switched off; the caller closes it). */
void owl_segmenter_reset(struct owl_segmenter *s);

#endif /* OWL_VAD_H_ */
