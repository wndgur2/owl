/* LED pattern sequencing (decisions #21). */

#include <zephyr/ztest.h>

#include "led_pattern.h"

static struct owl_led led;

static void before(void *f)
{
	owl_led_init(&led);
}

/* Drives the engine like led.c does and records the on-times between [from, to). */
struct trace {
	int64_t on_at[32];
	int64_t on_len[32];
	int n;
};

static void run(struct trace *t, int64_t from, int64_t to)
{
	int64_t now = from;
	bool was_on = false;
	int64_t since = 0;

	t->n = 0;
	while (now < to) {
		struct owl_led_out o = owl_led_step(&led, now);

		if (o.on && !was_on) {
			since = now;
		}
		if (!o.on && was_on && t->n < 32) {
			t->on_at[t->n] = since;
			t->on_len[t->n] = now - since;
			t->n++;
		}
		was_on = o.on;
		if (o.next_ms == OWL_LED_NEVER) {
			break;
		}
		now = MAX(o.next_ms, now);
	}
}

ZTEST(led, test_off_by_default)
{
	struct owl_led_out o = owl_led_step(&led, 0);

	zassert_false(o.on);
	zassert_equal(o.next_ms, OWL_LED_NEVER, "nothing scheduled: no timer, no wakeups");
}

ZTEST(led, test_one_shot_counts)
{
	static const struct {
		enum owl_led_pattern p;
		int blinks;
	} cases[] = {
		{OWL_LED_BOOKMARK, 1}, {OWL_LED_HINT_2S, 1}, {OWL_LED_HINT_5S, 3},
		{OWL_LED_REC_ON, 1},   {OWL_LED_REC_OFF, 2}, {OWL_LED_POWER_ON, 3},
		{OWL_LED_POWER_OFF, 1},
	};
	struct trace t;

	for (int i = 0; i < ARRAY_SIZE(cases); i++) {
		owl_led_init(&led);
		owl_led_play(&led, cases[i].p, 1000);
		run(&t, 1000, 10000);
		zassert_equal(t.n, cases[i].blinks, "pattern %d", cases[i].p);
		zassert_equal(t.on_at[0], 1000, "starts immediately");
	}
}

ZTEST(led, test_lengths_are_distinguishable)
{
	struct trace bookmark, rec_on, rec_off, power_on, hint5;

	owl_led_play(&led, OWL_LED_BOOKMARK, 0);
	run(&bookmark, 0, 5000);
	owl_led_play(&led, OWL_LED_REC_ON, 0);
	run(&rec_on, 0, 5000);
	owl_led_play(&led, OWL_LED_REC_OFF, 0);
	run(&rec_off, 0, 5000);
	owl_led_play(&led, OWL_LED_POWER_ON, 0);
	run(&power_on, 0, 5000);
	owl_led_play(&led, OWL_LED_HINT_5S, 0);
	run(&hint5, 0, 5000);

	zassert_true(bookmark.on_len[0] < rec_off.on_len[0], "very short < short");
	zassert_true(rec_off.on_len[0] * 3 <= rec_on.on_len[0], "short vs long");
	zassert_true(hint5.on_len[0] * 3 <= power_on.on_len[0], "3 fast vs 3 long");
}

ZTEST(led, test_heartbeat_every_10s)
{
	struct trace t;

	owl_led_set_status(&led, OWL_LED_ST_RECORDING, 0);
	run(&t, 0, 35000);
	zassert_equal(t.n, 3);
	zassert_equal(t.on_at[0], 10000);
	zassert_equal(t.on_at[1], 20000);
	zassert_equal(t.on_at[2], 30000);
	zassert_true(t.on_len[0] <= 50, "short");

	owl_led_set_status(&led, 0, 35000);
	run(&t, 35000, 100000);
	zassert_equal(t.n, 0, "stops with recording");
}

ZTEST(led, test_warnings_every_30s)
{
	struct trace t;

	owl_led_set_status(&led, OWL_LED_ST_BATTERY_LOW, 0);
	run(&t, 0, 62000);
	/* first at 1 s, then 31 s, 61 s; three short blinks each */
	zassert_equal(t.n, 9);
	zassert_equal(t.on_at[0], OWL_LED_WARN_FIRST_MS);
	zassert_equal(t.on_at[3], OWL_LED_WARN_FIRST_MS + OWL_LED_WARN_PERIOD_MS);

	owl_led_init(&led);
	owl_led_set_status(&led, OWL_LED_ST_STORAGE_FULL, 0);
	run(&t, 0, 32500);
	zassert_equal(t.n, 4, "two long blinks, twice");
	zassert_true(t.on_len[0] >= 400);
}

ZTEST(led, test_one_shot_defers_periodic)
{
	struct trace t;

	owl_led_set_status(&led, OWL_LED_ST_RECORDING, 0);
	/* Power-on pattern (2.1 s) straddles the 10 s heartbeat. */
	owl_led_play(&led, OWL_LED_POWER_ON, 9000);
	run(&t, 9000, 15000);
	zassert_equal(t.n, 4, "3 long + the deferred heartbeat");
	zassert_true(t.on_at[3] >= 9000 + 2100 + OWL_LED_GAP_MS,
		     "heartbeat waits for the one-shot, plus a visible gap");
	zassert_true(t.on_len[3] <= 50);
}

ZTEST(led, test_new_one_shot_replaces_old)
{
	struct trace t;

	owl_led_play(&led, OWL_LED_HINT_2S, 0);
	owl_led_step(&led, 0);
	owl_led_play(&led, OWL_LED_HINT_5S, 50); /* user kept holding */
	run(&t, 50, 5000);
	zassert_equal(t.n, 3);
}

ZTEST_SUITE(led, NULL, NULL, before, NULL, NULL);
