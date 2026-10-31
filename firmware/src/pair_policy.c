/* Pairing window / bond policy. See pair_policy.h. */

#include "pair_policy.h"

void owl_pair_policy_init(struct owl_pair_policy *p, int64_t now_ms)
{
	p->start_ms = now_ms;
	p->window_used = false;
}

bool owl_pair_allowed(const struct owl_pair_policy *p, int64_t now_ms, int bond_count)
{
	if (bond_count <= 0) {
		return true; /* nobody registered (new device, or after UNPAIR) */
	}
	return !p->window_used && now_ms - p->start_ms < OWL_PAIRING_WINDOW_MS;
}

void owl_pair_on_bonded(struct owl_pair_policy *p)
{
	p->window_used = true;
}
