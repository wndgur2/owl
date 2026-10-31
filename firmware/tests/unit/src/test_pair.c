/* Pairing window / bond policy (protocol §2.4). */

#include <zephyr/ztest.h>

#include "owl_proto.h"
#include "pair_policy.h"
#include "vectors.h"

#define T0 5000 /* BLE starts a few seconds after reset */

ZTEST(pair, test_constants_match_owlproto)
{
	zassert_equal(OWL_PAIRING_WINDOW_MS, VEC_PAIRING_WINDOW_MS);
	zassert_equal(OWL_UNENCRYPTED_TIMEOUT_MS, VEC_UNENCRYPTED_TIMEOUT_MS);
	zassert_equal(OWL_FLAG_PAIRING_OPEN, VEC_FLAG_PAIRING_OPEN);
}

ZTEST(pair, test_unbonded_device_always_pairs)
{
	struct owl_pair_policy p;

	owl_pair_policy_init(&p, T0);
	zassert_true(owl_pair_allowed(&p, T0, 0));
	zassert_true(owl_pair_allowed(&p, T0 + 10LL * 24 * 3600 * 1000, 0), "days later");
	owl_pair_on_bonded(&p);
	zassert_true(owl_pair_allowed(&p, T0 + 1, 0), "bond deleted again (UNPAIR)");
}

ZTEST(pair, test_window_after_power_on)
{
	struct owl_pair_policy p;

	owl_pair_policy_init(&p, T0);
	zassert_true(owl_pair_allowed(&p, T0, 1));
	zassert_true(owl_pair_allowed(&p, T0 + OWL_PAIRING_WINDOW_MS - 1, 1));
	zassert_false(owl_pair_allowed(&p, T0 + OWL_PAIRING_WINDOW_MS, 1), "window is [0, 120 s)");
	zassert_false(owl_pair_allowed(&p, T0 + 3600 * 1000, 1));
	zassert_false(owl_pair_allowed(&p, T0 + 3600 * 1000, 2), "stale extra bond");
}

ZTEST(pair, test_one_new_bond_per_window)
{
	struct owl_pair_policy p;

	owl_pair_policy_init(&p, T0);
	zassert_true(owl_pair_allowed(&p, T0 + 1000, 1), "old phone bonded, new phone may replace it");
	owl_pair_on_bonded(&p);
	zassert_false(owl_pair_allowed(&p, T0 + 2000, 1), "a second stranger may not replace it again");
	zassert_true(owl_pair_allowed(&p, T0 + 2000, 0), "unless the bond is gone");
}

ZTEST(pair, test_first_bond_closes_window_on_new_device)
{
	struct owl_pair_policy p;

	/* Fresh device registered 10 s after power-on: no second pairing in this boot. */
	owl_pair_policy_init(&p, T0);
	owl_pair_on_bonded(&p);
	zassert_false(owl_pair_allowed(&p, T0 + 10000, 1));

	/* Next power-on opens a new window. */
	owl_pair_policy_init(&p, T0);
	zassert_true(owl_pair_allowed(&p, T0 + 10000, 1));
}

ZTEST_SUITE(pair, NULL, NULL, NULL, NULL, NULL);
