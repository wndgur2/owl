/*
 * Battery measurement and System OFF. See power.h.
 *
 * System OFF is the nRF's deepest sleep: RAM is lost, only the GPIO "sense"
 * logic and (if armed) the GRTC timer stay alive. Waking up is a reset, so the
 * decision "really power on?" is made at the top of main() (power_policy.c).
 */

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/regulator.h>
#include <zephyr/pm/device.h>
#include <zephyr/sys/poweroff.h>
#include <zephyr/logging/log.h>
#include <zephyr/logging/log_ctrl.h>

#if defined(CONFIG_NRF_GRTC_START_SYSCOUNTER)
#include <zephyr/drivers/timer/nrf_grtc_timer.h>
#endif

#include "power.h"

LOG_MODULE_REGISTER(power, CONFIG_OWL_LOG_LEVEL);

#define USER_NODE DT_PATH(zephyr_user)

static const struct adc_dt_spec vbat_adc = ADC_DT_SPEC_GET_BY_IDX(USER_NODE, 0);
static const struct device *const vbat_reg = DEVICE_DT_GET(DT_NODELABEL(vbat_pwr));
static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET(USER_NODE, master_button_gpios);
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct device *const flash = DEVICE_DT_GET(DT_ALIAS(ext_flash));

static K_MUTEX_DEFINE(adc_lock);
static bool adc_ok;

int power_init(void)
{
	if (!adc_is_ready_dt(&vbat_adc) || adc_channel_setup_dt(&vbat_adc)) {
		LOG_ERR("battery ADC not ready");
		return -ENODEV;
	}
	adc_ok = true;
	return 0;
}

int power_battery_mv(void)
{
	int16_t raw;
	int32_t mv;
	struct adc_sequence seq = {
		.buffer = &raw,
		.buffer_size = sizeof(raw),
	};
	int err;

	if (!adc_ok) {
		return -ENODEV;
	}
	k_mutex_lock(&adc_lock, K_FOREVER);
	/* The divider sits behind a load switch so it doesn't drain the battery all day. */
	regulator_enable(vbat_reg);
	k_msleep(CONFIG_OWL_VBAT_SETTLE_MS);
	adc_sequence_init_dt(&vbat_adc, &seq);
	err = adc_read_dt(&vbat_adc, &seq);
	regulator_disable(vbat_reg);
	k_mutex_unlock(&adc_lock);
	if (err) {
		return err;
	}
	mv = raw;
	err = adc_raw_to_millivolts_dt(&vbat_adc, &mv);
	return err ? err : mv * 2; /* board divider is 1/2 */
}

void power_system_off(bool timer_wake)
{
	gpio_pin_set_dt(&led, 0);

	/* A level-triggered wake on a held button would wake us immediately. */
	for (int i = 0; i < 500 && gpio_pin_get_dt(&button) == 1; i++) {
		k_msleep(10);
	}

	pm_device_action_run(flash, PM_DEVICE_ACTION_SUSPEND); /* flash deep power-down */

	/* Button press (line goes low) wakes the chip from System OFF. */
	gpio_pin_interrupt_configure_dt(&button, GPIO_INT_LEVEL_ACTIVE);

#if defined(CONFIG_NRF_GRTC_START_SYSCOUNTER)
	if (timer_wake) {
		int err = z_nrf_grtc_wakeup_prepare((uint64_t)CONFIG_OWL_BATTERY_CHECK_S *
						    USEC_PER_SEC);

		if (err) {
			LOG_ERR("GRTC wake: %d", err);
		}
	}
#else
	ARG_UNUSED(timer_wake);
#endif

	LOG_INF("System OFF (%s)", timer_wake ? "timer + button wake" : "button wake only");
	LOG_PANIC(); /* flush logs */
	sys_poweroff();
}
