/*
 * Owl firmware - M0 bring-up.
 *
 * Checks every part we soldered, once, at boot, and then idles:
 *   1. battery voltage (AIN7, behind the vbat_pwr load switch)
 *   2. external SPI flash: JEDEC ID + erase/write/read of the last sector
 *      (the main app keeps that 4 KiB sector out of its LittleFS partition)
 *   3. PDM microphone: 1 s capture, prints peak and RMS
 * Then: master button press -> repeat the mic test.
 *       master button held 5 s -> System OFF; the next press must wake the
 *       board (it reboots and logs "reset cause: ... wakeup from System OFF").
 *
 * Feedback without a serial port (battery build): the user LED
 *   - one short blink every 2 s  = all checks passed
 *   - fast blinking              = a check failed (rebuild with uart.conf to see which)
 */

#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/drivers/regulator.h>
#include <zephyr/audio/dmic.h>
#include <zephyr/pm/device.h>
#include <zephyr/sys/poweroff.h>
#include <zephyr/logging/log.h>
#include <zephyr/logging/log_ctrl.h>

LOG_MODULE_REGISTER(owl, LOG_LEVEL_INF);

#define USER_NODE DT_PATH(zephyr_user)

static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET(USER_NODE, master_button_gpios);
static const struct adc_dt_spec vbat_adc = ADC_DT_SPEC_GET_BY_IDX(USER_NODE, 0);
static const struct device *const vbat_reg = DEVICE_DT_GET(DT_NODELABEL(vbat_pwr));
static const struct device *const flash = DEVICE_DT_GET(DT_ALIAS(ext_flash));
static const struct device *const dmic = DEVICE_DT_GET(DT_ALIAS(dmic0));

static bool all_ok = true;

/* ---------------------------------------------------------------- battery */

static int battery_read_mv(void)
{
	int16_t raw;
	int32_t mv;
	struct adc_sequence seq = {
		.buffer = &raw,
		.buffer_size = sizeof(raw),
	};

	regulator_enable(vbat_reg);
	k_msleep(10); /* let the divider settle */

	adc_sequence_init_dt(&vbat_adc, &seq);
	int err = adc_read_dt(&vbat_adc, &seq);

	regulator_disable(vbat_reg); /* the divider leaks current while on */
	if (err) {
		return err;
	}
	mv = raw;
	err = adc_raw_to_millivolts_dt(&vbat_adc, &mv);
	return err ? err : mv * 2; /* board divider is 1/2 */
}

static void check_battery(void)
{
	if (!adc_is_ready_dt(&vbat_adc) || adc_channel_setup_dt(&vbat_adc)) {
		LOG_ERR("battery: ADC not ready");
		all_ok = false;
		return;
	}
	/* vbat_pwr is regulator-boot-on, i.e. it starts with one reference held.
	 * Drop it, or the enable/disable pair below never switches the divider off.
	 */
	regulator_disable(vbat_reg);
	int mv = battery_read_mv();

	/* On USB with no battery attached this reads the charger output (~4.2 V) or noise. */
	LOG_INF("battery: %d mV", mv);
	if (mv < 0) {
		all_ok = false;
	}
}

/* ------------------------------------------------------------------ flash */

#define FLASH_SIZE   (16 * 1024 * 1024)
#define SECTOR_SIZE  4096
#define TEST_OFFSET  (FLASH_SIZE - SECTOR_SIZE)

static void check_flash(void)
{
	uint8_t id[3] = {0};
	uint8_t wbuf[32], rbuf[32];

	if (!device_is_ready(flash)) {
		LOG_ERR("flash: not ready (wiring? CS on D3? jedec-id in overlay?)");
		all_ok = false;
		return;
	}
	flash_read_jedec_id(flash, id);
	LOG_INF("flash: JEDEC %02x %02x %02x", id[0], id[1], id[2]);

	for (int i = 0; i < sizeof(wbuf); i++) {
		wbuf[i] = (uint8_t)(i * 7 + 3);
	}
	if (flash_erase(flash, TEST_OFFSET, SECTOR_SIZE) ||
	    flash_write(flash, TEST_OFFSET, wbuf, sizeof(wbuf)) ||
	    flash_read(flash, TEST_OFFSET, rbuf, sizeof(rbuf)) ||
	    memcmp(wbuf, rbuf, sizeof(wbuf)) != 0) {
		LOG_ERR("flash: erase/write/read test FAILED");
		all_ok = false;
	} else {
		LOG_INF("flash: erase/write/read OK");
	}

	/* Deep power-down until we need it (~1 uA instead of ~10s of uA). */
	pm_device_action_run(flash, PM_DEVICE_ACTION_SUSPEND);
}

/* -------------------------------------------------------------------- mic */

#define MIC_RATE      16000
#define BLOCK_MS      100
#define BLOCK_SAMPLES (MIC_RATE * BLOCK_MS / 1000)
#define BLOCK_BYTES   (BLOCK_SAMPLES * sizeof(int16_t))
#define BLOCK_COUNT   4

K_MEM_SLAB_DEFINE_STATIC(mic_slab, BLOCK_BYTES, BLOCK_COUNT, 4);

static uint32_t isqrt64(uint64_t x)
{
	uint64_t r = 0, bit = 1ULL << 62;

	while (bit > x) {
		bit >>= 2;
	}
	while (bit) {
		if (x >= r + bit) {
			x -= r + bit;
			r = (r >> 1) + bit;
		} else {
			r >>= 1;
		}
		bit >>= 2;
	}
	return (uint32_t)r;
}

static int mic_test(void)
{
	struct pcm_stream_cfg stream = {
		.pcm_rate = MIC_RATE,
		.pcm_width = 16,
		.block_size = BLOCK_BYTES,
		.mem_slab = &mic_slab,
	};
	struct dmic_cfg cfg = {
		.io = {
			.min_pdm_clk_freq = 1000000,
			.max_pdm_clk_freq = 3500000,
			.min_pdm_clk_dc = 40,
			.max_pdm_clk_dc = 60,
		},
		.streams = &stream,
		.channel = {
			.req_num_streams = 1,
			.req_num_chan = 1,
		},
	};
	/* MSM261 L/R pin strapping decides LEFT vs RIGHT. If RMS is ~0, try PDM_CHAN_RIGHT. */
	cfg.channel.req_chan_map_lo = dmic_build_channel_map(0, 0, PDM_CHAN_LEFT);

	int err = dmic_configure(dmic, &cfg);

	if (err) {
		LOG_ERR("mic: configure failed (%d)", err);
		return err;
	}
	err = dmic_trigger(dmic, DMIC_TRIGGER_START);
	if (err) {
		LOG_ERR("mic: start failed (%d)", err);
		return err;
	}

	uint64_t sum_sq = 0;
	uint32_t n = 0;
	int32_t peak = 0;

	/* 1 s = 10 blocks; drop the first one (PDM filter settling). */
	for (int b = 0; b < 11; b++) {
		void *buf;
		size_t size;

		err = dmic_read(dmic, 0, &buf, &size, 1000);
		if (err) {
			LOG_ERR("mic: read failed (%d)", err);
			break;
		}
		if (b > 0) {
			const int16_t *s = buf;

			for (uint32_t i = 0; i < size / sizeof(int16_t); i++) {
				int32_t v = s[i] < 0 ? -s[i] : s[i];

				peak = MAX(peak, v);
				sum_sq += (int64_t)s[i] * s[i];
				n++;
			}
		}
		k_mem_slab_free(&mic_slab, buf);
	}
	dmic_trigger(dmic, DMIC_TRIGGER_STOP);

	if (!err && n) {
		LOG_INF("mic: %u samples, peak %d, rms %u (of 32767)", n, peak,
			isqrt64(sum_sq / n));
	}
	return err;
}

/* ---------------------------------------------------------------- button */

static struct gpio_callback button_cb;
static struct k_work_delayable button_work;

static void system_off(void)
{
	LOG_INF("button: held 5 s -> System OFF, press again to wake");
	gpio_pin_set_dt(&led, 0);
	while (gpio_pin_get_dt(&button) == 1) { /* a held button would wake us at once */
		k_msleep(10);
	}
	pm_device_action_run(flash, PM_DEVICE_ACTION_SUSPEND);
	gpio_pin_interrupt_configure_dt(&button, GPIO_INT_LEVEL_ACTIVE);
	hwinfo_clear_reset_cause();
	LOG_PANIC();
	sys_poweroff();
}

static void button_work_fn(struct k_work *work)
{
	if (gpio_pin_get_dt(&button) != 1) { /* bounce */
		return;
	}
	for (int i = 0; i < 250; i++) { /* 5 s */
		k_msleep(20);
		if (gpio_pin_get_dt(&button) != 1) {
			LOG_INF("button: pressed -> mic test, talk now");
			mic_test();
			return;
		}
	}
	system_off();
}

static void button_isr(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	k_work_reschedule(&button_work, K_MSEC(30));
}

static int button_init(void)
{
	if (!gpio_is_ready_dt(&button) ||
	    gpio_pin_configure_dt(&button, GPIO_INPUT) ||
	    gpio_pin_interrupt_configure_dt(&button, GPIO_INT_EDGE_TO_ACTIVE)) {
		return -EIO;
	}
	k_work_init_delayable(&button_work, button_work_fn);
	gpio_init_callback(&button_cb, button_isr, BIT(button.pin));
	return gpio_add_callback(button.port, &button_cb);
}

/* ------------------------------------------------------------------ main */

int main(void)
{
	uint32_t cause = 0;

	gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
	LOG_INF("owl M0 bring-up");
	hwinfo_get_reset_cause(&cause);
	LOG_INF("reset cause: 0x%08x%s", cause,
		(cause & RESET_LOW_POWER_WAKE) ? " (wakeup from System OFF)" : "");
	hwinfo_clear_reset_cause();

	check_battery();
	check_flash();
	if (!device_is_ready(dmic) || mic_test()) {
		all_ok = false;
	}
	if (button_init()) {
		LOG_ERR("button: init failed");
		all_ok = false;
	}
	LOG_INF("bring-up %s", all_ok ? "PASSED" : "FAILED");

	while (1) {
		if (all_ok) {
			gpio_pin_set_dt(&led, 1);
			k_msleep(20);
			gpio_pin_set_dt(&led, 0);
			k_msleep(1980);
		} else {
			gpio_pin_toggle_dt(&led);
			k_msleep(100);
		}
	}
	return 0;
}
