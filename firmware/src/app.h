/*
 * Functions main.c provides to the other modules (it owns the cross-module
 * state: recording switch, battery reading, LED status).
 */

#ifndef OWL_APP_H_
#define OWL_APP_H_

#include <stdbool.h>
#include <stdint.h>

#include "owl_proto.h"

/* Snapshot for the STATUS characteristic. */
void app_fill_status(struct owl_status *s);

/* Recording switch from the button (OWL_CAUSE_BUTTON) or the app (OWL_CAUSE_APP).
 * Logs event 2/3 when the state changes. Returns an enum owl_result.
 */
int app_set_recording(bool on, uint8_t cause);

/* SET_CONFIG. Returns an enum owl_result. */
int app_set_config(uint8_t key, uint32_t value);

#endif /* OWL_APP_H_ */
