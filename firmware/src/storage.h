/*
 * Flash storage on LittleFS (decisions #14, #17, #18, #19).
 *
 *   /lfs/state        persisted struct owl_state (boot_count, next id, recording, ...)
 *   /lfs/seg/XXXXXXXX one segment per file, id in hex, bytes per protocol §3
 *
 * All functions are thread-safe (one mutex). Segments are only deleted by
 * storage_delete(), which the BLE layer calls after an ACK with a matching CRC.
 */

#ifndef OWL_STORAGE_H_
#define OWL_STORAGE_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "owl_proto.h"
#include "state.h"

/* Mounts the file system and loads the state. Does not touch boot_count. */
int storage_init(void);

/* After the power-on gate passes: boot_count++, power state ON, recover old files. */
int storage_boot(void);

/* Copy of the current persisted state. */
void storage_get_state(struct owl_state *out);
uint32_t storage_boot_count(void);

int storage_set_recording(bool on);
int storage_set_power_state(uint8_t power_state);
/* Returns an enum owl_result; persists on success. */
int storage_set_config(uint8_t key, uint32_t value);

/* --- audio segment writer (one open at a time; used by the recorder thread) --- */

/* Returns 0, -ENOSPC if storage is full, or another negative errno. */
int storage_seg_begin(uint8_t codec, uint8_t frame_ms, uint64_t start_uptime_ms);
/* Appends one frame as [len u16][bytes]. -ENOSPC when full (segment stays open). */
int storage_seg_append(const void *frame, uint16_t len);
/* Flushes and closes the segment and makes it listable. Empty segments are dropped. */
int storage_seg_end(void);

/* --- events (any thread) --- */

/* Appends one event record to the open event segment and syncs it to flash now. */
int storage_event(uint8_t event, uint8_t arg);
/* Closes the event segment (if it has events) so the next LIST includes it. */
int storage_events_rotate(void);

/* --- reading side (BLE) --- */

int storage_list(uint32_t after_id, struct owl_list_entry *out, int max);
bool storage_seg_info(uint32_t id, struct owl_list_entry *out);
/* Reads protocol-exact segment bytes. Returns bytes read (0 at end) or negative errno. */
int storage_read(uint32_t id, uint32_t offset, void *buf, size_t len);
/* CRC32 of the whole segment as sent over BLE. */
int storage_crc(uint32_t id, uint32_t *crc);
/* Deletes a listed segment. Returns 0 or -ENOENT. */
int storage_delete(uint32_t id);

/* --- space --- */

struct storage_usage {
	uint32_t used_kb;
	uint32_t total_kb;
	uint16_t pending;
	bool low;  /* >= 90 % used */
	bool full; /* below the reserve: recording must pause */
};

void storage_get_usage(struct storage_usage *u);

/* Called (from the deleting thread) when space was freed. */
typedef void (*storage_space_cb_t)(void);
void storage_set_space_cb(storage_space_cb_t cb);

/* Flush anything buffered (before System OFF). */
void storage_flush(void);

/* Tests only: unmount without closing anything and forget RAM state. */
void storage_test_power_cut(void);

#endif /* OWL_STORAGE_H_ */
