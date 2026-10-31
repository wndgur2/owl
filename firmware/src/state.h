/*
 * Small persisted device state: boot_count, next segment id, recording on/off,
 * why the device is powered off, and SET_CONFIG values.
 *
 * Stored as one tiny LittleFS file (decisions #18). LittleFS replaces a file
 * atomically on close, and the blob carries its own CRC, so a power cut while
 * saving leaves either the old or the new state, never a mix.
 */

#ifndef OWL_STATE_H_
#define OWL_STATE_H_

#include <stdbool.h>
#include <stdint.h>

#define OWL_STATE_BLOB_LEN 28

enum owl_power_state {
	OWL_PWR_ON = 0,
	OWL_PWR_OFF_BUTTON = 1,  /* 5 s press: never wakes on its own */
	OWL_PWR_OFF_BATTERY = 2, /* < 3.5 V: GRTC wakes hourly to check for charge */
};

struct owl_config {
	uint32_t opus_bitrate;   /* SET_CONFIG key 1, bps */
	uint8_t vad_sensitivity; /* key 2, 0 (least) .. 3 (most sensitive) */
	uint16_t segment_max_s;  /* key 3 */
};

#define OWL_CFG_BITRATE_MIN  6000
#define OWL_CFG_BITRATE_MAX  64000
#define OWL_CFG_VAD_MAX      3
#define OWL_CFG_SEG_MAX_MIN  10
#define OWL_CFG_SEG_MAX_MAX  3600

struct owl_state {
	uint32_t boot_count;
	uint32_t next_seg_id; /* ids start at 1, so LIST(after_id=0) returns everything */
	bool recording_on;
	uint8_t power_state;
	struct owl_config cfg;
};

void owl_state_defaults(struct owl_state *s);

void owl_state_pack(const struct owl_state *s, uint8_t out[OWL_STATE_BLOB_LEN]);
/* Returns 0, or -1 (and fills defaults) if the blob is missing, short, or corrupt. */
int owl_state_unpack(const uint8_t *in, int len, struct owl_state *s);

/* Applies a SET_CONFIG key/value. Returns an enum owl_result. */
int owl_config_set(struct owl_config *cfg, uint8_t key, uint32_t value);

#endif /* OWL_STATE_H_ */
