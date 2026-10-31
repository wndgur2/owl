/* Master button state machine. See button_sm.h. */

#include "button_sm.h"

void owl_btn_init(struct owl_btn_sm *sm, bool held_at_boot)
{
	sm->pressed = held_at_boot;
	sm->ignore_until_release = held_at_boot;
	sm->press_ms = 0;
	sm->hints_sent = 0;
}

enum owl_btn_action owl_btn_edge(struct owl_btn_sm *sm, bool pressed, int64_t now_ms)
{
	if (pressed == sm->pressed) {
		return OWL_BTN_NONE; /* duplicate edge */
	}
	sm->pressed = pressed;

	if (pressed) {
		sm->press_ms = now_ms;
		sm->hints_sent = 0;
		return OWL_BTN_NONE;
	}

	if (sm->ignore_until_release) {
		sm->ignore_until_release = false;
		return OWL_BTN_NONE;
	}

	int64_t held = now_ms - sm->press_ms;

	if (held >= OWL_BTN_VERYLONG_MS) {
		return OWL_BTN_POWER_OFF;
	}
	if (held >= OWL_BTN_LONG_MS) {
		return OWL_BTN_REC_TOGGLE;
	}
	return OWL_BTN_BOOKMARK;
}

enum owl_btn_action owl_btn_tick(struct owl_btn_sm *sm, int64_t now_ms)
{
	if (!sm->pressed || sm->ignore_until_release) {
		return OWL_BTN_NONE;
	}
	int64_t held = now_ms - sm->press_ms;

	/* One hint per tick; if a tick was late, the 5 s hint follows on the next one. */
	if (sm->hints_sent == 0 && held >= OWL_BTN_LONG_MS) {
		sm->hints_sent = 1;
		return OWL_BTN_HINT_2S;
	}
	if (sm->hints_sent == 1 && held >= OWL_BTN_VERYLONG_MS) {
		sm->hints_sent = 2;
		return OWL_BTN_HINT_5S;
	}
	return OWL_BTN_NONE;
}

int64_t owl_btn_next_deadline(const struct owl_btn_sm *sm)
{
	if (!sm->pressed || sm->ignore_until_release) {
		return -1;
	}
	switch (sm->hints_sent) {
	case 0:
		return sm->press_ms + OWL_BTN_LONG_MS;
	case 1:
		return sm->press_ms + OWL_BTN_VERYLONG_MS;
	default:
		return -1;
	}
}
