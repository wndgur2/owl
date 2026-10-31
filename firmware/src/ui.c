/*
 * LED and button glue. See ui.h.
 *
 * Button: the GPIO interrupt only (re)starts a 30 ms debounce timer; when it
 * fires we read the settled level and feed the edge to the state machine.
 * Hints at 2 s / 5 s come from a second timer armed at the state machine's
 * next deadline. Actions run on the app work queue, which may block on flash.
 */

#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>

#include "ui.h"
#include "workq.h"

LOG_MODULE_REGISTER(ui, CONFIG_OWL_LOG_LEVEL);

#define DEBOUNCE_MS 30

static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec button =
	GPIO_DT_SPEC_GET(DT_PATH(zephyr_user), master_button_gpios);

/* ------------------------------------------------------------------- LED */

static struct owl_led engine;
static K_MUTEX_DEFINE(led_lock);
static K_SEM_DEFINE(led_idle, 0, 1);

static void led_work_fn(struct k_work *w);
static K_WORK_DELAYABLE_DEFINE(led_work, led_work_fn);

static void led_run_locked(void)
{
	int64_t now = k_uptime_get();
	struct owl_led_out o = owl_led_step(&engine, now);

	gpio_pin_set_dt(&led, o.on);
	if (engine.steps == NULL) {
		k_sem_give(&led_idle);
	}
	if (o.next_ms == OWL_LED_NEVER) {
		k_work_cancel_delayable(&led_work);
	} else {
		k_work_reschedule(&led_work, K_MSEC(MAX(o.next_ms - now, 0)));
	}
}

static void led_work_fn(struct k_work *w)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	led_run_locked();
	k_mutex_unlock(&led_lock);
}

int ui_led_init(void)
{
	owl_led_init(&engine);
	if (!gpio_is_ready_dt(&led)) {
		return -ENODEV;
	}
	return gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
}

void ui_led_play(enum owl_led_pattern p)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	k_sem_reset(&led_idle);
	owl_led_play(&engine, p, k_uptime_get());
	led_run_locked();
	k_mutex_unlock(&led_lock);
}

void ui_led_set_status(uint8_t status_bits)
{
	k_mutex_lock(&led_lock, K_FOREVER);
	owl_led_set_status(&engine, status_bits, k_uptime_get());
	led_run_locked();
	k_mutex_unlock(&led_lock);
}

void ui_led_wait(int timeout_ms)
{
	k_sem_take(&led_idle, K_MSEC(timeout_ms));
}

/* ---------------------------------------------------------------- button */

static struct owl_btn_sm sm;
static ui_button_cb_t button_cb;
static struct gpio_callback gpio_cb;

static void debounce_fn(struct k_work *w);
static void hint_fn(struct k_work *w);
static K_WORK_DELAYABLE_DEFINE(debounce_work, debounce_fn);
static K_WORK_DELAYABLE_DEFINE(hint_work, hint_fn);

static void arm_hint(void)
{
	int64_t d = owl_btn_next_deadline(&sm);

	if (d < 0) {
		k_work_cancel_delayable(&hint_work);
	} else {
		k_work_reschedule_for_queue(&app_workq, &hint_work,
					    K_MSEC(MAX(d - k_uptime_get(), 0)));
	}
}

static void deliver(enum owl_btn_action a)
{
	if (a != OWL_BTN_NONE && button_cb) {
		button_cb(a);
	}
}

static void debounce_fn(struct k_work *w)
{
	bool pressed = gpio_pin_get_dt(&button) == 1;
	/* The edge happened DEBOUNCE_MS ago; measure hold time from there. */
	enum owl_btn_action a = owl_btn_edge(&sm, pressed, k_uptime_get() - DEBOUNCE_MS);

	arm_hint();
	deliver(a);
}

static void hint_fn(struct k_work *w)
{
	enum owl_btn_action a = owl_btn_tick(&sm, k_uptime_get());

	arm_hint();
	deliver(a);
}

static void button_isr(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	k_work_reschedule_for_queue(&app_workq, &debounce_work, K_MSEC(DEBOUNCE_MS));
}

int ui_button_init(void)
{
	if (!gpio_is_ready_dt(&button)) {
		return -ENODEV;
	}
	return gpio_pin_configure_dt(&button, GPIO_INPUT);
}

bool ui_button_pressed(void)
{
	return gpio_pin_get_dt(&button) == 1;
}

void ui_button_start(ui_button_cb_t cb, bool held_at_boot)
{
	button_cb = cb;
	owl_btn_init(&sm, held_at_boot);
	gpio_init_callback(&gpio_cb, button_isr, BIT(button.pin));
	gpio_add_callback(button.port, &gpio_cb);
	gpio_pin_interrupt_configure_dt(&button, GPIO_INT_EDGE_BOTH);
	/* If it was released between the boot check and now, catch that edge. */
	k_work_reschedule_for_queue(&app_workq, &debounce_work, K_MSEC(DEBOUNCE_MS));
}
