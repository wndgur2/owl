/*
 * Owl firmware: boot sequence and the glue between modules.
 *
 *   storage.c  LittleFS: segments, events, persisted state   (M1)
 *   audio.c    PDM -> VAD -> Opus -> segments                 (M1/M2)
 *   ble.c      GATT service, CONTROL ops, DATA streaming      (M3)
 *   ui.c       button state machine, LED patterns             (M3)
 *   power.c    battery ADC, System OFF, GRTC wake             (M5 logic)
 *
 * Boot:
 *   1. mount flash, read why we were off (state file)
 *   2. power-on gate: off-by-button needs a 5 s press; off-by-battery needs
 *      >= 3.8 V (hourly GRTC wake). Otherwise straight back to System OFF.
 *   3. boot_count++, repair half-written segments, log event 7
 *   4. start recorder, BLE, button, battery monitor
 */

#include <stdlib.h>

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <app_version.h>

#include "app.h"
#include "audio.h"
#include "ble.h"
#include "power.h"
#include "power_policy.h"
#include "storage.h"
#include "ui.h"
#include "workq.h"

LOG_MODULE_REGISTER(owl, CONFIG_OWL_LOG_LEVEL);

/* ------------------------------------------------------------- work queue */

K_THREAD_STACK_DEFINE(app_wq_stack, 3072);
struct k_work_q app_workq;

void app_workq_start(void)
{
	/* Preemptible and below the recorder, so flash work never starves audio. */
	k_work_queue_start(&app_workq, app_wq_stack, K_THREAD_STACK_SIZEOF(app_wq_stack), 9,
			   NULL);
}

/* ------------------------------------------------------------ app state */

static K_MUTEX_DEFINE(app_lock);
static bool recording_on;
static atomic_t battery_mv;
static struct owl_batt_policy batt;
static atomic_t powering_off;

static void update_ui_status(void)
{
	uint8_t bits = 0;

	if (audio_is_capturing()) {
		bits |= OWL_LED_ST_RECORDING;
	}
	if (batt.low) {
		bits |= OWL_LED_ST_BATTERY_LOW;
	}
	if (audio_is_full()) {
		bits |= OWL_LED_ST_STORAGE_FULL;
	}
	ui_led_set_status(bits);
	ble_notify_status();
}

static void status_work_fn(struct k_work *w)
{
	update_ui_status();
}

static K_WORK_DEFINE(status_work, status_work_fn);

static void on_audio_state(void)
{
	k_work_submit_to_queue(&app_workq, &status_work);
}

void app_fill_status(struct owl_status *s)
{
	struct storage_usage u;

	storage_get_usage(&u);
	s->fw_ver[0] = APP_VERSION_MAJOR;
	s->fw_ver[1] = APP_VERSION_MINOR;
	s->fw_ver[2] = APP_PATCHLEVEL;
	s->battery_mv = (uint16_t)MAX(atomic_get(&battery_mv), 0);
	s->flags = 0;
	if (recording_on) {
		s->flags |= OWL_FLAG_RECORDING;
	}
	if (u.low) {
		s->flags |= OWL_FLAG_STORAGE_LOW;
	}
	if (audio_is_full()) {
		s->flags |= OWL_FLAG_STORAGE_FULL;
	}
	s->storage_used_kb = u.used_kb;
	s->storage_total_kb = u.total_kb;
	s->pending_segments = u.pending;
	s->boot_count = storage_boot_count();
}

int app_set_recording(bool on, uint8_t cause)
{
	int res = OWL_RESULT_OK;

	k_mutex_lock(&app_lock, K_FOREVER);
	if (recording_on != on) {
		if (storage_set_recording(on)) {
			res = OWL_RESULT_BUSY;
		} else {
			recording_on = on;
			/* State-changing events record the result (protocol §3.3). */
			storage_event(on ? OWL_EVT_RECORDING_ON : OWL_EVT_RECORDING_OFF, cause);
			audio_set_enabled(on);
			ui_led_play(on ? OWL_LED_REC_ON : OWL_LED_REC_OFF);
			LOG_INF("recording %s (%s)", on ? "on" : "off",
				cause == OWL_CAUSE_APP ? "app" : "button");
		}
	}
	k_mutex_unlock(&app_lock);
	on_audio_state();
	return res;
}

int app_set_config(uint8_t key, uint32_t value)
{
	int res = storage_set_config(key, value);

	if (res == OWL_RESULT_OK) {
		struct owl_state st;

		storage_get_state(&st);
		audio_apply_config(&st.cfg);
		LOG_INF("config key %u = %u", key, value);
	}
	return res;
}

/* ------------------------------------------------------------- power off */

static FUNC_NORETURN void power_off(uint8_t cause)
{
	bool battery = cause == OWL_CAUSE_BATTERY;

	atomic_set(&powering_off, 1);
	LOG_INF("powering off (%s)", battery ? "battery" : "button");
	ui_led_play(OWL_LED_POWER_OFF);
	ble_stop();
	audio_shutdown();
	storage_event(OWL_EVT_POWER_OFF, cause);
	storage_set_power_state(battery ? OWL_PWR_OFF_BATTERY : OWL_PWR_OFF_BUTTON);
	storage_flush();
	ui_led_wait(2000);
	/* Only a battery shutdown wakes by itself to look for a charger (decisions #20). */
	power_system_off(battery);
	CODE_UNREACHABLE;
}

/* ---------------------------------------------------------------- button */

static void on_button(enum owl_btn_action a)
{
	if (atomic_get(&powering_off)) {
		return;
	}
	switch (a) {
	case OWL_BTN_BOOKMARK:
		ui_led_play(OWL_LED_BOOKMARK);
		storage_event(OWL_EVT_BOOKMARK, 0);
		ble_notify_status();
		break;
	case OWL_BTN_HINT_2S:
		ui_led_play(OWL_LED_HINT_2S);
		break;
	case OWL_BTN_HINT_5S:
		ui_led_play(OWL_LED_HINT_5S);
		break;
	case OWL_BTN_REC_TOGGLE:
		app_set_recording(!recording_on, OWL_CAUSE_BUTTON);
		break;
	case OWL_BTN_POWER_OFF:
		power_off(OWL_CAUSE_BUTTON);
		break;
	default:
		break;
	}
}

/* --------------------------------------------------------------- battery */

static void battery_work_fn(struct k_work *w);
static K_WORK_DELAYABLE_DEFINE(battery_work, battery_work_fn);

static void battery_work_fn(struct k_work *w)
{
	int mv = power_battery_mv();
	int old = atomic_set(&battery_mv, mv);
	bool was_low = batt.low;

	switch (owl_batt_policy_update(&batt, mv)) {
	case OWL_BATT_WARN:
		LOG_WRN("battery low: %d mV", mv);
		storage_event(OWL_EVT_BATTERY_LOW, 0);
		break;
	case OWL_BATT_SHUTDOWN:
		LOG_WRN("battery empty: %d mV", mv);
		power_off(OWL_CAUSE_BATTERY);
		break;
	default:
		break;
	}
	if (batt.low != was_low || abs(mv - old) >= 20) {
		update_ui_status();
	}
	/* Below cutoff: confirm quickly instead of waiting a full period. */
	k_work_schedule_for_queue(&app_workq, &battery_work,
				  batt.below_cutoff ? K_SECONDS(5)
						    : K_SECONDS(CONFIG_OWL_BATTERY_PERIOD_S));
}

/* ------------------------------------------------------------------ boot */

/* After a wake from System OFF by the button: is it held until 5 s of uptime? */
static bool button_held_5s(void)
{
	while (k_uptime_get() < OWL_BTN_VERYLONG_MS) {
		if (!ui_button_pressed()) {
			return false;
		}
		k_msleep(20);
	}
	return true;
}

static void fatal_blink(const char *what)
{
	LOG_ERR("FATAL: %s", what);
	for (;;) {
		ui_led_play(OWL_LED_HINT_5S); /* fast blinks, like the M0 failure signal */
		k_msleep(1000);
	}
}

int main(void)
{
	struct owl_state st;
	int err;

	LOG_INF("Owl firmware %s", APP_VERSION_STRING);
	ui_led_init();
	ui_button_init();
	power_init();
	app_workq_start();

	err = storage_init();
	if (err) {
		fatal_blink("storage");
	}
	storage_get_state(&st);

	/* 1. power-on gate */
	int mv = power_battery_mv();
	bool held = false;

	if (st.power_state != OWL_PWR_ON && ui_button_pressed()) {
		held = button_held_5s();
	}
	struct owl_boot_decision d = owl_boot_decide(st.power_state, mv, held);

	LOG_INF("boot: was %u, %d mV, button %s -> %s", st.power_state, mv,
		held ? "held 5 s" : "no", d.power_on ? "ON" : "stay off");
	if (!d.power_on) {
		power_system_off(d.timer_wake);
	}

	/* 2. we are on */
	err = storage_boot();
	if (err) {
		LOG_ERR("storage boot: %d", err);
	}
	storage_get_state(&st);
	storage_event(OWL_EVT_POWER_ON, d.cause);
	ui_led_play(OWL_LED_POWER_ON);
	LOG_INF("boot_count %u, recording %s", st.boot_count, st.recording_on ? "on" : "off");

	atomic_set(&battery_mv, mv);
	owl_batt_policy_init(&batt);
	recording_on = st.recording_on;

	/* 3. subsystems */
	if (audio_init(on_audio_state) == 0) {
		audio_apply_config(&st.cfg);
		audio_set_enabled(st.recording_on);
		audio_start();
	} else {
		LOG_ERR("no microphone: BLE sync still works, recording does not");
	}

	err = ble_init();
	if (err) {
		LOG_ERR("BLE init: %d", err);
	}

	ui_button_start(on_button, ui_button_pressed());
	k_work_schedule_for_queue(&app_workq, &battery_work, K_NO_WAIT);
	update_ui_status();
	return 0;
}
