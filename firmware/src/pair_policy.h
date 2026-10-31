/*
 * Who may pair (protocol §2.4, decisions #33-#35). Pure logic: ble.c feeds it
 * the bond count and the time since BLE started, Zephyr's SMP asks it through
 * the pairing_accept callback.
 *
 *   no phone bonded            -> pairing accepted at any time
 *   a phone is bonded          -> only in the first 120 s after power-on,
 *                                 and only until one new bond is made there
 *
 * One bond at a time: once a new bond completes, ble.c deletes the others, so
 * pairing inside the window *replaces* the old phone (lost-phone recovery).
 */

#ifndef OWL_PAIR_POLICY_H_
#define OWL_PAIR_POLICY_H_

#include <stdbool.h>
#include <stdint.h>

/* Pairing window after power-on (BLE start), protocol §2.4. */
#define OWL_PAIRING_WINDOW_MS 120000
/* A link that is still unencrypted after this long is dropped, so a stranger
 * cannot hold the device's only connection (protocol §2.4).
 */
#define OWL_UNENCRYPTED_TIMEOUT_MS 60000

struct owl_pair_policy {
	int64_t start_ms;  /* uptime when BLE started */
	bool window_used;  /* a new bond was made inside the window */
};

void owl_pair_policy_init(struct owl_pair_policy *p, int64_t now_ms);

/* Would a new pairing be accepted right now? Also STATUS flag bit4. */
bool owl_pair_allowed(const struct owl_pair_policy *p, int64_t now_ms, int bond_count);

/* A pairing finished with a bond. Closes the window for further pairings. */
void owl_pair_on_bonded(struct owl_pair_policy *p);

#endif /* OWL_PAIR_POLICY_H_ */
