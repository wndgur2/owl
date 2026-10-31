/*
 * Battery voltage and System OFF (decisions #8, #20; PRD D8a, D10).
 */

#ifndef OWL_POWER_H_
#define OWL_POWER_H_

#include <stdbool.h>

int power_init(void);
/* Battery voltage in mV, or negative errno. Takes ~10 ms (divider settle). */
int power_battery_mv(void);

/*
 * Enters System OFF; never returns. The master button always wakes the chip
 * (wake = reset, main() starts over). timer_wake additionally arms the GRTC to
 * wake it in CONFIG_OWL_BATTERY_CHECK_S seconds (only for battery shutdowns).
 */
void power_system_off(bool timer_wake);

#endif /* OWL_POWER_H_ */
