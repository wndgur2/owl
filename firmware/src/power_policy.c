/* Battery / power-on rules. See power_policy.h. */

#include "owl_proto.h"
#include "power_policy.h"
#include "state.h"

void owl_batt_policy_init(struct owl_batt_policy *p)
{
	p->low = false;
	p->below_cutoff = 0;
}

enum owl_batt_action owl_batt_policy_update(struct owl_batt_policy *p, int battery_mv)
{
	if (battery_mv <= 0) {
		return OWL_BATT_NONE; /* ADC error: do not act on garbage */
	}

	if (battery_mv < OWL_BATT_CUTOFF_MV) {
		if (++p->below_cutoff >= OWL_BATT_CUTOFF_COUNT) {
			p->below_cutoff = OWL_BATT_CUTOFF_COUNT;
			return OWL_BATT_SHUTDOWN;
		}
	} else {
		p->below_cutoff = 0;
	}

	if (!p->low && battery_mv < OWL_BATT_WARN_MV) {
		p->low = true;
		return OWL_BATT_WARN;
	}
	if (p->low && battery_mv >= OWL_BATT_WARN_CLEAR_MV) {
		p->low = false;
	}
	return OWL_BATT_NONE;
}

struct owl_boot_decision owl_boot_decide(uint8_t power_state, int battery_mv, bool button_held)
{
	struct owl_boot_decision d = {0};

	switch (power_state) {
	case OWL_PWR_OFF_BUTTON:
		/* Off by the user: charging or a battery swap must not turn it on. */
		d.power_on = button_held;
		d.cause = OWL_CAUSE_BUTTON;
		d.timer_wake = false;
		break;
	case OWL_PWR_OFF_BATTERY:
		if (battery_mv >= OWL_BATT_CHARGED_MV) {
			d.power_on = true;
			d.cause = button_held ? OWL_CAUSE_BUTTON : OWL_CAUSE_CHARGED;
		} else if (button_held && battery_mv >= OWL_BATT_WARN_MV) {
			/* User insists and there is some margin above cutoff. */
			d.power_on = true;
			d.cause = OWL_CAUSE_BUTTON;
		} else {
			d.power_on = false;
			d.timer_wake = true;
		}
		break;
	default:
		/* Was running: reset, crash, flashing, or first boot ever. */
		d.power_on = true;
		d.cause = OWL_CAUSE_OTHER;
		break;
	}
	return d;
}
