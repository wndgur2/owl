/* LED pattern sequencer. See led_pattern.h. */

#include <stddef.h>

#include "led_pattern.h"

#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

/*
 * Durations in ms. "short" ~ 60 ms, "long" ~ 500 ms; the LED draws ~0.9 mA
 * while on, so these keep the average well under 0.01 mA (decisions #21).
 */
static const uint16_t p_bookmark[] = {25};
static const uint16_t p_hint_2s[] = {150};
static const uint16_t p_hint_5s[] = {70, 90, 70, 90, 70};
static const uint16_t p_rec_on[] = {600};
static const uint16_t p_rec_off[] = {100, 200, 100};
static const uint16_t p_power_on[] = {500, 300, 500, 300, 500};
static const uint16_t p_power_off[] = {1000};
static const uint16_t p_heartbeat[] = {30};
static const uint16_t p_battery_low[] = {60, 200, 60, 200, 60};
static const uint16_t p_storage_full[] = {500, 300, 500};

#define P(a) {a, sizeof(a) / sizeof(a[0])}

static const struct {
	const uint16_t *steps;
	uint8_t n;
} patterns[OWL_LED_PATTERN_COUNT] = {
	[OWL_LED_BOOKMARK] = P(p_bookmark),
	[OWL_LED_HINT_2S] = P(p_hint_2s),
	[OWL_LED_HINT_5S] = P(p_hint_5s),
	[OWL_LED_REC_ON] = P(p_rec_on),
	[OWL_LED_REC_OFF] = P(p_rec_off),
	[OWL_LED_POWER_ON] = P(p_power_on),
	[OWL_LED_POWER_OFF] = P(p_power_off),
	[OWL_LED_HEARTBEAT] = P(p_heartbeat),
	[OWL_LED_BATTERY_LOW] = P(p_battery_low),
	[OWL_LED_STORAGE_FULL] = P(p_storage_full),
};

/* Periodic slots, in priority order when several are due at once. */
enum { SLOT_FULL, SLOT_BATT, SLOT_HEART, SLOT_COUNT };

static const struct {
	uint8_t status_bit;
	enum owl_led_pattern pattern;
	uint32_t period_ms;
	uint32_t first_ms;
} slots[SLOT_COUNT] = {
	[SLOT_FULL] = {OWL_LED_ST_STORAGE_FULL, OWL_LED_STORAGE_FULL, OWL_LED_WARN_PERIOD_MS,
		       OWL_LED_WARN_FIRST_MS},
	[SLOT_BATT] = {OWL_LED_ST_BATTERY_LOW, OWL_LED_BATTERY_LOW, OWL_LED_WARN_PERIOD_MS,
		       OWL_LED_WARN_FIRST_MS},
	[SLOT_HEART] = {OWL_LED_ST_RECORDING, OWL_LED_HEARTBEAT, OWL_LED_HEARTBEAT_PERIOD_MS,
			OWL_LED_HEARTBEAT_PERIOD_MS},
};

const uint16_t *owl_led_pattern_steps(enum owl_led_pattern p, uint8_t *nsteps)
{
	*nsteps = patterns[p].n;
	return patterns[p].steps;
}

void owl_led_init(struct owl_led *led)
{
	led->steps = NULL;
	led->nsteps = 0;
	led->idx = 0;
	led->t0 = 0;
	led->status = 0;
	led->quiet_until = 0;
	for (int i = 0; i < SLOT_COUNT; i++) {
		led->due[i] = OWL_LED_NEVER;
	}
}

static void start(struct owl_led *led, enum owl_led_pattern p, int64_t now_ms)
{
	led->steps = patterns[p].steps;
	led->nsteps = patterns[p].n;
	led->idx = 0;
	led->t0 = now_ms;
}

void owl_led_play(struct owl_led *led, enum owl_led_pattern p, int64_t now_ms)
{
	start(led, p, now_ms);
}

void owl_led_set_status(struct owl_led *led, uint8_t status, int64_t now_ms)
{
	for (int i = 0; i < SLOT_COUNT; i++) {
		bool was = led->status & slots[i].status_bit;
		bool is = status & slots[i].status_bit;

		if (is && !was) {
			led->due[i] = now_ms + slots[i].first_ms;
		} else if (!is) {
			led->due[i] = OWL_LED_NEVER;
		}
	}
	led->status = status;
}

struct owl_led_out owl_led_step(struct owl_led *led, int64_t now_ms)
{
	for (;;) {
		if (led->steps) {
			/* Advance past every step that has already ended (catches up if late). */
			while (led->idx < led->nsteps && now_ms >= led->t0 + led->steps[led->idx]) {
				led->t0 += led->steps[led->idx];
				led->idx++;
			}
			if (led->idx < led->nsteps) {
				return (struct owl_led_out){
					.on = (led->idx % 2) == 0,
					.next_ms = led->t0 + led->steps[led->idx],
				};
			}
			led->steps = NULL;
			led->quiet_until = led->t0 + OWL_LED_GAP_MS;
		}

		int64_t next = OWL_LED_NEVER;
		int due_slot = -1;

		for (int i = 0; i < SLOT_COUNT; i++) {
			int64_t at = MAX(led->due[i], led->quiet_until);

			if (led->due[i] == OWL_LED_NEVER) {
				continue;
			}
			if (at <= now_ms && due_slot < 0) {
				due_slot = i;
			}
			if (at < next) {
				next = at;
			}
		}
		if (due_slot < 0) {
			return (struct owl_led_out){.on = false, .next_ms = next};
		}
		/* Re-arm from now (not from the old due time) so a long one-shot can't cause a burst. */
		led->due[due_slot] = now_ms + slots[due_slot].period_ms;
		start(led, slots[due_slot].pattern, now_ms);
	}
}
