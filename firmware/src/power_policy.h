/*
 * Battery and power-on rules (PRD D8a, D10; decisions #8, #13, #20). Pure logic.
 *
 *   < 3.6 V : warn once (event 5 + LED), re-armed above 3.7 V
 *   < 3.5 V : three readings in a row -> flush and System OFF, GRTC wakes hourly
 *   >= 3.8 V while off for battery -> power on by itself (event 7, cause 3)
 *   off by button: only a 5 s press turns it back on
 */

#ifndef OWL_POWER_POLICY_H_
#define OWL_POWER_POLICY_H_

#include <stdbool.h>
#include <stdint.h>

#define OWL_BATT_WARN_MV      3600
#define OWL_BATT_WARN_CLEAR_MV 3700
#define OWL_BATT_CUTOFF_MV    3500
#define OWL_BATT_CHARGED_MV   3800
/* Readings below cutoff needed before shutting down; ignores dips under radio/flash load. */
#define OWL_BATT_CUTOFF_COUNT 3

enum owl_batt_action {
	OWL_BATT_NONE,
	OWL_BATT_WARN,     /* just crossed below 3.6 V */
	OWL_BATT_SHUTDOWN, /* confirmed below 3.5 V */
};

struct owl_batt_policy {
	bool low;
	uint8_t below_cutoff;
};

void owl_batt_policy_init(struct owl_batt_policy *p);
enum owl_batt_action owl_batt_policy_update(struct owl_batt_policy *p, int battery_mv);

struct owl_boot_decision {
	bool power_on;
	uint8_t cause;    /* enum owl_cause for event 7, when power_on */
	bool timer_wake;  /* when staying off: arm the hourly GRTC wake */
};

/*
 * power_state: enum owl_power_state saved before the last System OFF.
 * button_held: woke by the master button and it was held for 5 s.
 */
struct owl_boot_decision owl_boot_decide(uint8_t power_state, int battery_mv, bool button_held);

#endif /* OWL_POWER_POLICY_H_ */
