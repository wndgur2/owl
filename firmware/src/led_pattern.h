/*
 * LED pattern sequencer for the single green LED (decisions #21). Pure logic.
 *
 * One-shot patterns (bookmark, button hints, state changes) play immediately and
 * replace whatever one-shot was playing. Periodic patterns (recording heartbeat,
 * battery low, storage full) play when due and never interrupt a one-shot; they
 * wait for it to finish. The LED is otherwise off.
 *
 * The glue (led.c) calls owl_led_step() from a timer, sets the GPIO to .on and
 * re-arms the timer for .next_ms. No busy loops, no sleeping thread.
 */

#ifndef OWL_LED_PATTERN_H_
#define OWL_LED_PATTERN_H_

#include <stdbool.h>
#include <stdint.h>

enum owl_led_pattern {
	OWL_LED_BOOKMARK,     /* 1 very short */
	OWL_LED_HINT_2S,      /* 1 blink */
	OWL_LED_HINT_5S,      /* 3 fast */
	OWL_LED_REC_ON,       /* 1 long */
	OWL_LED_REC_OFF,      /* 2 short */
	OWL_LED_POWER_ON,     /* 3 long */
	OWL_LED_POWER_OFF,    /* 1 long, then off */
	OWL_LED_HEARTBEAT,    /* periodic: recording, 1 short every 10 s */
	OWL_LED_BATTERY_LOW,  /* periodic: 3 short every 30 s */
	OWL_LED_STORAGE_FULL, /* periodic: 2 long every 30 s */
	OWL_LED_PATTERN_COUNT,
};

/* Status bits that switch the periodic patterns on. */
#define OWL_LED_ST_RECORDING    (1U << 0) /* recording on and actually capturing */
#define OWL_LED_ST_BATTERY_LOW  (1U << 1)
#define OWL_LED_ST_STORAGE_FULL (1U << 2)

#define OWL_LED_HEARTBEAT_PERIOD_MS 10000
#define OWL_LED_WARN_PERIOD_MS      30000
/* First warning blink shortly after the condition starts, so it is noticed. */
#define OWL_LED_WARN_FIRST_MS       1000

/* Dark time after a one-shot before a periodic pattern may start, so the two
 * never merge into one long blink. */
#define OWL_LED_GAP_MS 400

#define OWL_LED_NEVER INT64_MAX

struct owl_led_out {
	bool on;
	int64_t next_ms; /* absolute; OWL_LED_NEVER if nothing is scheduled */
};

struct owl_led {
	const uint16_t *steps; /* durations, alternating on/off, starting with on */
	uint8_t nsteps;
	uint8_t idx;
	int64_t t0; /* start of steps[idx] */
	uint8_t status;
	int64_t quiet_until; /* no periodic pattern before this */
	int64_t due[3];      /* storage full, battery low, heartbeat */
};

void owl_led_init(struct owl_led *led);
void owl_led_play(struct owl_led *led, enum owl_led_pattern p, int64_t now_ms);
void owl_led_set_status(struct owl_led *led, uint8_t status, int64_t now_ms);
struct owl_led_out owl_led_step(struct owl_led *led, int64_t now_ms);

/* For tests: the step table of a pattern. */
const uint16_t *owl_led_pattern_steps(enum owl_led_pattern p, uint8_t *nsteps);

#endif /* OWL_LED_PATTERN_H_ */
