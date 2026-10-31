/*
 * Recorder: PDM mic -> VAD -> Opus (or PCM16) -> segments on flash.
 *
 * Two threads:
 *   audio  : reads 20 ms PDM blocks, runs VAD and the encoder (CPU heavy)
 *   writer : takes encoded frames from a RAM ring and writes them to flash
 * The ring decouples the two, so a slow flash erase (up to ~0.4 s on W25Q128)
 * never makes the PDM driver drop samples.
 */

#ifndef OWL_AUDIO_H_
#define OWL_AUDIO_H_

#include <stdbool.h>

#include "state.h"

/* Called from recorder threads whenever capturing/full changes. Keep it short. */
typedef void (*audio_state_cb_t)(void);

int audio_init(audio_state_cb_t cb);
/* Starts the threads (after the power-on gate). */
void audio_start(void);

/* The user's recording switch (button 2 s / SET_RECORDING). */
void audio_set_enabled(bool on);
/* VAD sensitivity, segment max length and bitrate; applied from the next frame/segment. */
void audio_apply_config(const struct owl_config *cfg);

bool audio_is_capturing(void);
/* Recording is paused because flash is full (STATUS bit3). */
bool audio_is_full(void);

/* Stops capture and waits until the open segment is on flash (before System OFF). */
void audio_shutdown(void);

#endif /* OWL_AUDIO_H_ */
