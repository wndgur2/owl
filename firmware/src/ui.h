/*
 * UI glue: the green user LED (led_pattern.c) and the master button (button_sm.c).
 */

#ifndef OWL_UI_H_
#define OWL_UI_H_

#include <stdbool.h>
#include <stdint.h>

#include "button_sm.h"
#include "led_pattern.h"

int ui_led_init(void);
void ui_led_play(enum owl_led_pattern p);
void ui_led_set_status(uint8_t status_bits);
/* Blocks until the current one-shot pattern has finished (max timeout_ms). */
void ui_led_wait(int timeout_ms);

typedef void (*ui_button_cb_t)(enum owl_btn_action action);

int ui_button_init(void);
bool ui_button_pressed(void);
/* Starts delivering actions (on the app work queue). held_at_boot: see button_sm.h. */
void ui_button_start(ui_button_cb_t cb, bool held_at_boot);

#endif /* OWL_UI_H_ */
