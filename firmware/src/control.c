/* CONTROL request dispatch. See control.h. */

#include "control.h"

/* Minimum payload length (after op, seq) per opcode. */
static int payload_len_for(uint8_t op)
{
	switch (op) {
	case OWL_OP_TIME_SYNC:
		return 10; /* unix_ms u64, tz_offset_min i16 */
	case OWL_OP_LIST_SEGMENTS:
		return 5; /* after_id u32, max u8 */
	case OWL_OP_FETCH_SEGMENT:
		return 8; /* id u32, offset u32 */
	case OWL_OP_ACK_SEGMENT:
		return 8; /* id u32, crc32 u32 */
	case OWL_OP_ABORT:
		return 0;
	case OWL_OP_SET_RECORDING:
		return 1; /* on u8 */
	case OWL_OP_SET_CONFIG:
		return 5; /* key u8, value u32 */
	default:
		return -1;
	}
}

int owl_control_list_capacity(size_t resp_max)
{
	if (resp_max < 4) {
		return 0;
	}
	int n = (int)((resp_max - 4) / OWL_LIST_ENTRY_LEN);

	return n > OWL_LIST_MAX ? OWL_LIST_MAX : n;
}

size_t owl_control_handle(const struct owl_ctl_ops *ops, const uint8_t *req, size_t len,
			  uint8_t *resp, size_t resp_max)
{
	if (len == 0 || resp_max < 3) {
		return 0;
	}

	const uint8_t op = req[0];
	const uint8_t seq = len >= 2 ? req[1] : 0;
	const uint8_t *p = &req[2];
	size_t out = 3;

	resp[0] = op | OWL_RESPONSE_BIT;
	resp[1] = seq;

	int need = payload_len_for(op);

	if (need < 0) {
		resp[2] = OWL_RESULT_UNKNOWN_OP;
		return out;
	}
	if (len < 2 || len - 2 < (size_t)need) {
		resp[2] = OWL_RESULT_BAD_LENGTH;
		return out;
	}

	int result = OWL_RESULT_OK;

	switch (op) {
	case OWL_OP_TIME_SYNC: {
		uint32_t boot_count = 0;
		uint64_t uptime_ms = 0;

		ops->time_sync(owl_get_u64(&p[0]), (int16_t)owl_get_u16(&p[8]), &boot_count,
			       &uptime_ms);
		if (resp_max >= out + 12) {
			owl_put_u32(&resp[out], boot_count);
			owl_put_u64(&resp[out + 4], uptime_ms);
			out += 12;
		}
		break;
	}
	case OWL_OP_LIST_SEGMENTS: {
		struct owl_list_entry entries[OWL_LIST_MAX];
		int max = p[4];
		int cap = owl_control_list_capacity(resp_max);

		if (max > cap) {
			max = cap;
		}
		int n = max > 0 ? ops->list(owl_get_u32(&p[0]), entries, max) : 0;

		if (n < 0) {
			n = 0;
		}
		resp[out++] = (uint8_t)n;
		for (int i = 0; i < n; i++) {
			owl_list_entry_pack(&entries[i], &resp[out]);
			out += OWL_LIST_ENTRY_LEN;
		}
		break;
	}
	case OWL_OP_FETCH_SEGMENT:
		result = ops->fetch(owl_get_u32(&p[0]), owl_get_u32(&p[4]));
		break;
	case OWL_OP_ACK_SEGMENT:
		result = ops->ack(owl_get_u32(&p[0]), owl_get_u32(&p[4]));
		break;
	case OWL_OP_ABORT:
		ops->abort();
		break;
	case OWL_OP_SET_RECORDING:
		/* Only 0 and 1 are defined; anything else is a malformed request. */
		result = p[0] > 1 ? OWL_RESULT_BAD_LENGTH : ops->set_recording(p[0] == 1);
		break;
	case OWL_OP_SET_CONFIG:
		result = ops->set_config(p[0], owl_get_u32(&p[1]));
		break;
	}

	resp[2] = (uint8_t)result;
	return out;
}
