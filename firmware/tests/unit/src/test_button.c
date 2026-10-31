/* Button state machine timing (decisions #13, #21). */

#include <zephyr/ztest.h>

#include "button_sm.h"

static struct owl_btn_sm sm;

static void before(void *f)
{
	owl_btn_init(&sm, false);
}

/* Press at t0, run ticks every 10 ms until release at t0 + held. */
static enum owl_btn_action press(int64_t t0, int64_t held, int *hint2, int *hint5)
{
	*hint2 = *hint5 = 0;
	zassert_equal(owl_btn_edge(&sm, true, t0), OWL_BTN_NONE);
	for (int64_t t = t0; t < t0 + held; t += 10) {
		enum owl_btn_action a = owl_btn_tick(&sm, t);

		if (a == OWL_BTN_HINT_2S) {
			(*hint2)++;
			zassert_true(t - t0 >= OWL_BTN_LONG_MS && t - t0 < OWL_BTN_LONG_MS + 10);
		} else if (a == OWL_BTN_HINT_5S) {
			(*hint5)++;
			zassert_true(t - t0 >= OWL_BTN_VERYLONG_MS);
		} else {
			zassert_equal(a, OWL_BTN_NONE);
		}
	}
	return owl_btn_edge(&sm, false, t0 + held);
}

ZTEST(button, test_short_press_is_bookmark)
{
	int h2, h5;

	zassert_equal(press(1000, 150, &h2, &h5), OWL_BTN_BOOKMARK);
	zassert_equal(h2 + h5, 0);
	zassert_equal(press(5000, 1999, &h2, &h5), OWL_BTN_BOOKMARK);
	zassert_equal(h2, 0);
}

ZTEST(button, test_two_seconds_toggles_recording)
{
	int h2, h5;

	zassert_equal(press(1000, 2000, &h2, &h5), OWL_BTN_REC_TOGGLE);
	zassert_equal(press(9000, 3500, &h2, &h5), OWL_BTN_REC_TOGGLE);
	zassert_equal(h2, 1, "one hint at 2 s");
	zassert_equal(h5, 0);
	zassert_equal(press(20000, 4999, &h2, &h5), OWL_BTN_REC_TOGGLE);
}

ZTEST(button, test_five_seconds_powers_off)
{
	int h2, h5;

	zassert_equal(press(1000, 5000, &h2, &h5), OWL_BTN_POWER_OFF);
	zassert_equal(press(9000, 8000, &h2, &h5), OWL_BTN_POWER_OFF);
	zassert_equal(h2, 1);
	zassert_equal(h5, 1, "one hint at 5 s, no repeats");
}

ZTEST(button, test_deadlines)
{
	zassert_equal(owl_btn_next_deadline(&sm), -1, "idle");
	owl_btn_edge(&sm, true, 100);
	zassert_equal(owl_btn_next_deadline(&sm), 2100);
	zassert_equal(owl_btn_tick(&sm, 2100), OWL_BTN_HINT_2S);
	zassert_equal(owl_btn_next_deadline(&sm), 5100);
	/* A late timer still delivers both hints, one per tick, in order. */
	owl_btn_init(&sm, false);
	owl_btn_edge(&sm, true, 0);
	zassert_equal(owl_btn_tick(&sm, 7000), OWL_BTN_HINT_2S);
	zassert_equal(owl_btn_tick(&sm, 7000), OWL_BTN_HINT_5S);
	zassert_equal(owl_btn_next_deadline(&sm), -1);
}

ZTEST(button, test_power_on_press_is_ignored)
{
	int h2, h5;

	/* Woken by a 5 s press: that same press is still down when we start. */
	owl_btn_init(&sm, true);
	zassert_equal(owl_btn_tick(&sm, 6000), OWL_BTN_NONE, "no hints for the boot press");
	zassert_equal(owl_btn_next_deadline(&sm), -1);
	zassert_equal(owl_btn_edge(&sm, false, 6500), OWL_BTN_NONE, "release is swallowed");
	zassert_equal(press(8000, 100, &h2, &h5), OWL_BTN_BOOKMARK, "next press works");
}

ZTEST(button, test_duplicate_edges)
{
	zassert_equal(owl_btn_edge(&sm, false, 10), OWL_BTN_NONE, "release while idle");
	owl_btn_edge(&sm, true, 100);
	zassert_equal(owl_btn_edge(&sm, true, 900), OWL_BTN_NONE);
	zassert_equal(owl_btn_edge(&sm, false, 300 + 100), OWL_BTN_BOOKMARK,
		      "hold time counted from the first press edge");
}

ZTEST_SUITE(button, NULL, NULL, before, NULL, NULL);
