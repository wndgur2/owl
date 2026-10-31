/* Persisted state codec. See state.h. */

#include <string.h>

#include "owl_proto.h"
#include "state.h"

#define STATE_MAGIC   "OWST"
#define STATE_VERSION 1

void owl_state_defaults(struct owl_state *s)
{
	memset(s, 0, sizeof(*s));
	s->boot_count = 0;
	s->next_seg_id = 1;
	/* Factory default: recording on. After that, the user's choice sticks (decisions #14). */
	s->recording_on = true;
	s->power_state = OWL_PWR_ON;
	s->cfg.opus_bitrate = 16000;
	s->cfg.vad_sensitivity = 2;
	s->cfg.segment_max_s = 300;
}

/*
 * Layout: magic[4] ver u8 rec u8 pwr u8 vad u8 | boot u32 | next_id u32 |
 *         bitrate u32 | seg_max u16 | reserved u16 | crc32 u32
 */
void owl_state_pack(const struct owl_state *s, uint8_t out[OWL_STATE_BLOB_LEN])
{
	memset(out, 0, OWL_STATE_BLOB_LEN);
	memcpy(&out[0], STATE_MAGIC, 4);
	out[4] = STATE_VERSION;
	out[5] = s->recording_on ? 1 : 0;
	out[6] = s->power_state;
	out[7] = s->cfg.vad_sensitivity;
	owl_put_u32(&out[8], s->boot_count);
	owl_put_u32(&out[12], s->next_seg_id);
	owl_put_u32(&out[16], s->cfg.opus_bitrate);
	owl_put_u16(&out[20], s->cfg.segment_max_s);
	owl_put_u32(&out[24], owl_crc32(0, out, 24));
}

int owl_state_unpack(const uint8_t *in, int len, struct owl_state *s)
{
	owl_state_defaults(s);
	if (len < OWL_STATE_BLOB_LEN || memcmp(in, STATE_MAGIC, 4) != 0 ||
	    in[4] != STATE_VERSION || owl_get_u32(&in[24]) != owl_crc32(0, in, 24)) {
		return -1;
	}
	struct owl_state t;

	owl_state_defaults(&t);
	t.recording_on = in[5] != 0;
	t.power_state = in[6] <= OWL_PWR_OFF_BATTERY ? in[6] : OWL_PWR_ON;
	t.boot_count = owl_get_u32(&in[8]);
	t.next_seg_id = owl_get_u32(&in[12]);
	if (t.next_seg_id == 0) {
		t.next_seg_id = 1;
	}
	/* Run config through the same validation as SET_CONFIG; bad values keep defaults. */
	owl_config_set(&t.cfg, 1, owl_get_u32(&in[16]));
	owl_config_set(&t.cfg, 2, in[7]);
	owl_config_set(&t.cfg, 3, owl_get_u16(&in[20]));
	*s = t;
	return 0;
}

int owl_config_set(struct owl_config *cfg, uint8_t key, uint32_t value)
{
	/*
	 * The protocol has no "invalid value" result yet, so unknown keys and
	 * out-of-range values both answer BAD_LENGTH (closest fit). See PR notes.
	 */
	switch (key) {
	case OWL_CFG_OPUS_BITRATE:
		if (value < OWL_CFG_BITRATE_MIN || value > OWL_CFG_BITRATE_MAX) {
			return OWL_RESULT_BAD_LENGTH;
		}
		cfg->opus_bitrate = value;
		return OWL_RESULT_OK;
	case OWL_CFG_VAD_SENSITIVITY:
		if (value > OWL_CFG_VAD_MAX) {
			return OWL_RESULT_BAD_LENGTH;
		}
		cfg->vad_sensitivity = (uint8_t)value;
		return OWL_RESULT_OK;
	case OWL_CFG_SEGMENT_MAX_S:
		if (value < OWL_CFG_SEG_MAX_MIN || value > OWL_CFG_SEG_MAX_MAX) {
			return OWL_RESULT_BAD_LENGTH;
		}
		cfg->segment_max_s = (uint16_t)value;
		return OWL_RESULT_OK;
	default:
		return OWL_RESULT_BAD_LENGTH;
	}
}
