/*
 * CONTROL characteristic request parser / response encoder (§2.2).
 *
 * Pure logic: the actual work is delegated to an ops table, so the BLE glue
 * (ble.c) plugs in real storage/recorder calls and the tests plug in fakes.
 */

#ifndef OWL_CONTROL_H_
#define OWL_CONTROL_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "owl_proto.h"

struct owl_ctl_ops {
	/* Device ignores the phone's clock; it only answers where it is in its own time. */
	void (*time_sync)(uint64_t unix_ms, int16_t tz_offset_min, uint32_t *boot_count,
			  uint64_t *uptime_ms);
	/* Up to max entries with id > after_id, ascending. Returns the count. */
	int (*list)(uint32_t after_id, struct owl_list_entry *out, int max);
	/* Each returns an enum owl_result. */
	int (*fetch)(uint32_t id, uint32_t offset);
	int (*ack)(uint32_t id, uint32_t crc32);
	void (*abort)(void);
	int (*set_recording)(bool on);
	int (*set_config)(uint8_t key, uint32_t value);
	/* Delete every bond after the response is sent (§2.4). */
	int (*unpair)(void);
};

/*
 * Handles one CONTROL write. Writes the response into resp (at most resp_max
 * bytes, i.e. ATT_MTU - 3 of the current connection) and returns its length,
 * or 0 when no response can be sent (empty write).
 *
 * Payloads longer than expected are accepted and the tail is ignored, so a
 * newer app can append fields (§7). Shorter ones get OWL_RESULT_BAD_LENGTH.
 */
size_t owl_control_handle(const struct owl_ctl_ops *ops, const uint8_t *req, size_t len,
			  uint8_t *resp, size_t resp_max);

/* How many LIST entries fit in one response of resp_max bytes (<= OWL_LIST_MAX). */
int owl_control_list_capacity(size_t resp_max);

#endif /* OWL_CONTROL_H_ */
