/* Byte layouts of protocol/ble-protocol.md. See owl_proto.h. */

#include <string.h>

#include "owl_proto.h"

void owl_status_pack(const struct owl_status *s, uint8_t out[OWL_STATUS_LEN])
{
	memset(out, 0, OWL_STATUS_LEN);
	out[0] = OWL_PROTO_VER;
	out[1] = s->fw_ver[0];
	out[2] = s->fw_ver[1];
	out[3] = s->fw_ver[2];
	owl_put_u16(&out[4], s->battery_mv);
	out[6] = s->flags;
	/* out[7] reserved */
	owl_put_u32(&out[8], s->storage_used_kb);
	owl_put_u32(&out[12], s->storage_total_kb);
	owl_put_u16(&out[16], s->pending_segments);
	/* out[18..19] reserved */
	owl_put_u32(&out[20], s->boot_count);
}

void owl_seg_header_pack(const struct owl_seg_header *h, uint8_t out[OWL_SEG_HDR_LEN])
{
	memcpy(&out[0], OWL_SEG_MAGIC, 4);
	out[4] = h->version;
	out[5] = h->type;
	out[6] = h->codec;
	out[7] = h->frame_ms;
	owl_put_u32(&out[8], h->id);
	owl_put_u32(&out[12], h->boot_count);
	owl_put_u64(&out[16], h->start_uptime_ms);
	owl_put_u16(&out[24], h->sample_rate_div100);
	out[26] = h->channels;
	out[27] = 0;
	owl_put_u32(&out[28], h->payload_len);
}

int owl_seg_header_unpack(const uint8_t in[OWL_SEG_HDR_LEN], struct owl_seg_header *h)
{
	if (memcmp(in, OWL_SEG_MAGIC, 4) != 0) {
		return -1;
	}
	h->version = in[4];
	h->type = in[5];
	h->codec = in[6];
	h->frame_ms = in[7];
	h->id = owl_get_u32(&in[8]);
	h->boot_count = owl_get_u32(&in[12]);
	h->start_uptime_ms = owl_get_u64(&in[16]);
	h->sample_rate_div100 = owl_get_u16(&in[24]);
	h->channels = in[26];
	h->payload_len = owl_get_u32(&in[28]);
	return 0;
}

void owl_seg_header_init(struct owl_seg_header *h, uint8_t type, uint8_t codec,
			 uint8_t frame_ms, uint32_t id, uint32_t boot_count,
			 uint64_t start_uptime_ms)
{
	memset(h, 0, sizeof(*h));
	h->version = OWL_SEGMENT_VERSION;
	h->type = type;
	h->codec = codec;
	h->frame_ms = frame_ms;
	h->id = id;
	h->boot_count = boot_count;
	h->start_uptime_ms = start_uptime_ms;
	/* Event segments carry the same fixed audio fields as owlproto.py writes them. */
	h->sample_rate_div100 = 160;
	h->channels = 1;
}

void owl_event_pack(uint64_t uptime_ms, uint8_t event, uint8_t arg,
		    uint8_t out[OWL_EVENT_REC_LEN])
{
	owl_put_u64(&out[0], uptime_ms);
	out[8] = event;
	out[9] = arg;
}

void owl_list_entry_pack(const struct owl_list_entry *e, uint8_t out[OWL_LIST_ENTRY_LEN])
{
	owl_put_u32(&out[0], e->id);
	out[4] = e->type;
	owl_put_u32(&out[5], e->size);
}

/*
 * Bitwise-with-table CRC-32. A 16-entry (nibble) table keeps it at 64 bytes of
 * flash while still being ~4x faster than the bit-by-bit loop. Reflected
 * polynomial 0xEDB88320, init/xorout 0xFFFFFFFF, same as zlib.crc32().
 */
uint32_t owl_crc32(uint32_t crc, const void *data, size_t len)
{
	static const uint32_t table[16] = {
		0x00000000, 0x1db71064, 0x3b6e20c8, 0x26d930ac,
		0x76dc4190, 0x6b6b51f4, 0x4db26158, 0x5005713c,
		0xedb88320, 0xf00f9344, 0xd6d6a3e8, 0xcb61b38c,
		0x9b64c2b0, 0x86d3d2d4, 0xa00ae278, 0xbdbdf21c,
	};
	const uint8_t *p = data;

	crc = ~crc;
	while (len--) {
		crc ^= *p++;
		crc = (crc >> 4) ^ table[crc & 0x0f];
		crc = (crc >> 4) ^ table[crc & 0x0f];
	}
	return ~crc;
}
