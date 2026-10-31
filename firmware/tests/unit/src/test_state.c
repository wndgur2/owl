/* Persisted state, config validation, boot/power-on decisions, battery policy. */

#include <string.h>
#include <zephyr/ztest.h>

#include "owl_proto.h"
#include "power_policy.h"
#include "state.h"

ZTEST(state, test_roundtrip_and_boot_count)
{
	struct owl_state s, back;
	uint8_t blob[OWL_STATE_BLOB_LEN];

	owl_state_defaults(&s);
	zassert_equal(s.next_seg_id, 1, "ids start at 1 so LIST(after=0) sees all");
	zassert_true(s.recording_on);

	/* Simulate three boots: load, ++, save. */
	for (int boot = 1; boot <= 3; boot++) {
		owl_state_pack(&s, blob);
		zassert_ok(owl_state_unpack(blob, sizeof(blob), &back));
		back.boot_count++;
		s = back;
	}
	zassert_equal(s.boot_count, 3);

	s.recording_on = false; /* decisions #14: stays off across reboots */
	s.power_state = OWL_PWR_OFF_BATTERY;
	s.next_seg_id = 0x1234;
	s.cfg.vad_sensitivity = 1;
	s.cfg.segment_max_s = 60;
	s.cfg.opus_bitrate = 12000;
	owl_state_pack(&s, blob);
	zassert_ok(owl_state_unpack(blob, sizeof(blob), &back));
	zassert_mem_equal(&back, &s, sizeof(s));
}

ZTEST(state, test_corrupt_blob_gives_defaults)
{
	struct owl_state s, back, def;
	uint8_t blob[OWL_STATE_BLOB_LEN];

	owl_state_defaults(&def);
	owl_state_defaults(&s);
	s.boot_count = 77;
	owl_state_pack(&s, blob);

	blob[9] ^= 1; /* flip a bit in boot_count */
	zassert_equal(owl_state_unpack(blob, sizeof(blob), &back), -1);
	zassert_mem_equal(&back, &def, sizeof(def));

	zassert_equal(owl_state_unpack(blob, -1, &back), -1, "missing file");
	zassert_equal(owl_state_unpack(blob, 10, &back), -1, "short file");
}

ZTEST(state, test_config_validation)
{
	struct owl_config c;
	struct owl_state s;

	owl_state_defaults(&s);
	c = s.cfg;
	zassert_equal(c.opus_bitrate, 16000);
	zassert_equal(c.vad_sensitivity, 2);
	zassert_equal(c.segment_max_s, 300);

	zassert_equal(owl_config_set(&c, OWL_CFG_OPUS_BITRATE, 12000), OWL_RESULT_OK);
	zassert_equal(c.opus_bitrate, 12000);
	zassert_equal(owl_config_set(&c, OWL_CFG_OPUS_BITRATE, 100), OWL_RESULT_BAD_LENGTH);
	zassert_equal(c.opus_bitrate, 12000, "rejected value leaves config alone");
	zassert_equal(owl_config_set(&c, OWL_CFG_VAD_SENSITIVITY, 3), OWL_RESULT_OK);
	zassert_equal(owl_config_set(&c, OWL_CFG_VAD_SENSITIVITY, 4), OWL_RESULT_BAD_LENGTH);
	zassert_equal(owl_config_set(&c, OWL_CFG_SEGMENT_MAX_S, 5), OWL_RESULT_BAD_LENGTH);
	zassert_equal(owl_config_set(&c, OWL_CFG_SEGMENT_MAX_S, 120), OWL_RESULT_OK);
	zassert_equal(c.segment_max_s, 120);
	zassert_equal(owl_config_set(&c, 99, 1), OWL_RESULT_BAD_LENGTH);
}

ZTEST(state, test_boot_decisions)
{
	struct owl_boot_decision d;

	d = owl_boot_decide(OWL_PWR_ON, 3000, false);
	zassert_true(d.power_on, "a running device that reset comes back");
	zassert_equal(d.cause, OWL_CAUSE_OTHER);

	d = owl_boot_decide(OWL_PWR_OFF_BUTTON, 4200, false);
	zassert_false(d.power_on, "button-off never turns on by itself");
	zassert_false(d.timer_wake, "and arms no timer");
	d = owl_boot_decide(OWL_PWR_OFF_BUTTON, 3300, true);
	zassert_true(d.power_on);
	zassert_equal(d.cause, OWL_CAUSE_BUTTON);

	d = owl_boot_decide(OWL_PWR_OFF_BATTERY, 3799, false);
	zassert_false(d.power_on);
	zassert_true(d.timer_wake, "keep checking hourly");
	d = owl_boot_decide(OWL_PWR_OFF_BATTERY, 3800, false);
	zassert_true(d.power_on);
	zassert_equal(d.cause, OWL_CAUSE_CHARGED);
	d = owl_boot_decide(OWL_PWR_OFF_BATTERY, 3650, true);
	zassert_true(d.power_on);
	zassert_equal(d.cause, OWL_CAUSE_BUTTON);
	d = owl_boot_decide(OWL_PWR_OFF_BATTERY, 3550, true);
	zassert_false(d.power_on, "too empty even for the button");
	zassert_true(d.timer_wake);
}

ZTEST(state, test_battery_policy)
{
	struct owl_batt_policy p;

	owl_batt_policy_init(&p);
	zassert_equal(owl_batt_policy_update(&p, 3900), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3590), OWL_BATT_WARN);
	zassert_equal(owl_batt_policy_update(&p, 3580), OWL_BATT_NONE, "warn only once");
	zassert_equal(owl_batt_policy_update(&p, 3650), OWL_BATT_NONE, "no re-arm below 3.7 V");
	zassert_equal(owl_batt_policy_update(&p, 3590), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3710), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3590), OWL_BATT_WARN, "re-armed");

	/* Cutoff needs three readings in a row; a dip under load does not count. */
	zassert_equal(owl_batt_policy_update(&p, 3490), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3520), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3490), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3480), OWL_BATT_NONE);
	zassert_equal(owl_batt_policy_update(&p, 3470), OWL_BATT_SHUTDOWN);

	owl_batt_policy_init(&p);
	zassert_equal(owl_batt_policy_update(&p, -5), OWL_BATT_NONE, "ADC error ignored");
	zassert_equal(owl_batt_policy_update(&p, 0), OWL_BATT_NONE);
}

ZTEST_SUITE(state, NULL, NULL, NULL, NULL, NULL);
