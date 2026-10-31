/*
 * Master button state machine (decisions #13, #21). Pure logic, time in ms.
 *
 *   release before 2 s        -> BOOKMARK
 *   still held at 2 s         -> HINT_2S  (LED: "release now = recording toggle")
 *   release between 2 and 5 s -> REC_TOGGLE
 *   still held at 5 s         -> HINT_5S  (LED: "release now = power off")
 *   release after 5 s         -> POWER_OFF
 *
 * Actions fire on release, so the user can change their mind by holding longer.
 * The glue (button.c) debounces the GPIO and feeds clean edges in here.
 */

#ifndef OWL_BUTTON_SM_H_
#define OWL_BUTTON_SM_H_

#include <stdbool.h>
#include <stdint.h>

#define OWL_BTN_LONG_MS     2000
#define OWL_BTN_VERYLONG_MS 5000

enum owl_btn_action {
	OWL_BTN_NONE,
	OWL_BTN_BOOKMARK,
	OWL_BTN_REC_TOGGLE,
	OWL_BTN_POWER_OFF,
	OWL_BTN_HINT_2S,
	OWL_BTN_HINT_5S,
};

struct owl_btn_sm {
	bool pressed;
	/* Set when the button was already down at boot (the 5 s power-on press):
	 * that release must not also count as a press. */
	bool ignore_until_release;
	int64_t press_ms;
	uint8_t hints_sent;
};

void owl_btn_init(struct owl_btn_sm *sm, bool held_at_boot);

/* Feed a debounced edge. Returns the action for a release, else NONE. */
enum owl_btn_action owl_btn_edge(struct owl_btn_sm *sm, bool pressed, int64_t now_ms);

/* Call at (or after) owl_btn_next_deadline() while held. Returns a HINT or NONE. */
enum owl_btn_action owl_btn_tick(struct owl_btn_sm *sm, int64_t now_ms);

/* Absolute time of the next hint, or -1 if nothing is pending. */
int64_t owl_btn_next_deadline(const struct owl_btn_sm *sm);

#endif /* OWL_BUTTON_SM_H_ */
