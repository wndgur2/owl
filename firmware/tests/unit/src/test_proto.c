/* Byte layouts vs. tools/owlproto.py fixtures (vectors.h). */

#include <string.h>
#include <zephyr/ztest.h>

#include "owl_proto.h"
#include "vectors.h"

ZTEST(proto, test_sizes_match_spec)
{
	zassert_equal(OWL_DATA_CHUNK_MAX, VEC_DATA_CHUNK_MAX);
	zassert_equal(OWL_DATA_CHUNK_MAX, 236);
	zassert_equal(OWL_LIST_MAX, VEC_LIST_MAX);
	zassert_equal(OWL_LIST_MAX, 26);
	zassert_equal(sizeof(vec_status), OWL_STATUS_LEN);
	zassert_equal(sizeof(vec_seg_header_audio), OWL_SEG_HDR_LEN);
}

ZTEST(proto, test_status_pack)
{
	struct owl_status s = {
		.fw_ver = {0, 3, 0},
		.battery_mv = 3850,
		.flags = OWL_FLAG_RECORDING | OWL_FLAG_STORAGE_LOW,
		.storage_used_kb = 1024,
		.storage_total_kb = 16380,
		.pending_segments = 5,
		.boot_count = 42,
	};
	uint8_t out[OWL_STATUS_LEN];

	owl_status_pack(&s, out);
	zassert_mem_equal(out, vec_status, sizeof(vec_status));
}

ZTEST(proto, test_seg_header_pack_unpack)
{
	struct owl_seg_header h, back;
	uint8_t out[OWL_SEG_HDR_LEN];

	owl_seg_header_init(&h, OWL_SEG_AUDIO, OWL_CODEC_OPUS, 20, 99, 3, 600000);
	h.payload_len = 1234;
	owl_seg_header_pack(&h, out);
	zassert_mem_equal(out, vec_seg_header_audio, sizeof(out));

	zassert_ok(owl_seg_header_unpack(vec_seg_header_audio, &back));
	zassert_equal(back.id, 99);
	zassert_equal(back.boot_count, 3);
	zassert_equal(back.start_uptime_ms, 600000);
	zassert_equal(back.sample_rate_div100, 160);
	zassert_equal(back.payload_len, 1234);

	out[0] = 'X';
	zassert_equal(owl_seg_header_unpack(out, &back), -1, "bad magic accepted");
}

/* Builds an audio segment the way storage.c lays it out: header, then [len][frame]. */
ZTEST(proto, test_audio_segment_bytes_and_crc)
{
	static const uint8_t f0[3] = {0x11, 0x11, 0x11};
	static uint8_t f1[40];
	static const uint8_t f2[3] = {0xfc, 0xff, 0xfe};
	const uint8_t *frames[] = {f0, f1, f2};
	const uint16_t lens[] = {3, 40, 3};
	uint8_t seg[128];
	size_t pos = OWL_SEG_HDR_LEN;
	struct owl_seg_header h;

	for (int i = 0; i < 40; i++) {
		f1[i] = (uint8_t)i;
	}
	for (int i = 0; i < 3; i++) {
		owl_put_u16(&seg[pos], lens[i]);
		memcpy(&seg[pos + 2], frames[i], lens[i]);
		pos += 2 + lens[i];
	}
	owl_seg_header_init(&h, OWL_SEG_AUDIO, OWL_CODEC_OPUS, 20, 7, 2, 123456789012ULL);
	h.payload_len = pos - OWL_SEG_HDR_LEN;
	owl_seg_header_pack(&h, seg);

	zassert_equal(pos, sizeof(vec_audio_segment));
	zassert_mem_equal(seg, vec_audio_segment, pos);
	zassert_equal(owl_crc32(0, seg, pos), VEC_AUDIO_SEGMENT_CRC);

	/* CRC is incremental: streaming it in DATA-sized pieces gives the same value. */
	uint32_t c = 0;

	for (size_t off = 0; off < pos; off += 5) {
		c = owl_crc32(c, &seg[off], MIN(5, pos - off));
	}
	zassert_equal(c, VEC_AUDIO_SEGMENT_CRC);
}

ZTEST(proto, test_crc32_check_value)
{
	zassert_equal(owl_crc32(0, "123456789", 9), VEC_CRC_123456789);
	zassert_equal(VEC_CRC_123456789, 0xcbf43926u);
	zassert_equal(owl_crc32(0, NULL, 0), 0);
}

ZTEST(proto, test_event_segment_bytes)
{
	uint8_t seg[OWL_SEG_HDR_LEN + 4 * OWL_EVENT_REC_LEN];
	struct owl_seg_header h;
	const struct {
		uint64_t t;
		uint8_t e, a;
	} ev[] = {
		{1000, OWL_EVT_BOOKMARK, 0},
		{2000, OWL_EVT_RECORDING_OFF, OWL_CAUSE_BUTTON},
		{3000, OWL_EVT_RECORDING_ON, OWL_CAUSE_APP},
		{0x100000001ULL, OWL_EVT_POWER_OFF, OWL_CAUSE_BATTERY},
	};

	owl_seg_header_init(&h, OWL_SEG_EVENTS, OWL_CODEC_PCM16, 0, 12, 3, 500);
	h.payload_len = 4 * OWL_EVENT_REC_LEN;
	owl_seg_header_pack(&h, seg);
	for (int i = 0; i < 4; i++) {
		owl_event_pack(ev[i].t, ev[i].e, ev[i].a,
			       &seg[OWL_SEG_HDR_LEN + i * OWL_EVENT_REC_LEN]);
	}
	zassert_equal(sizeof(seg), sizeof(vec_event_segment));
	zassert_mem_equal(seg, vec_event_segment, sizeof(seg));
}

ZTEST(proto, test_list_entries)
{
	const struct owl_list_entry e[] = {
		{1, OWL_SEG_AUDIO, 1000},
		{2, OWL_SEG_EVENTS, 52},
		{0xdeadbeef, OWL_SEG_AUDIO, 0x123456},
	};
	uint8_t out[1 + 3 * OWL_LIST_ENTRY_LEN];

	out[0] = 3;
	for (int i = 0; i < 3; i++) {
		owl_list_entry_pack(&e[i], &out[1 + i * OWL_LIST_ENTRY_LEN]);
	}
	zassert_mem_equal(out, vec_list_payload, sizeof(vec_list_payload));
}

ZTEST(proto, test_data_chunk_header)
{
	/* chunk_segment(7, blob, start=236): header is [id u32][offset u32]. */
	zassert_equal(owl_get_u32(&vec_data_chunk_236[0]), 7);
	zassert_equal(owl_get_u32(&vec_data_chunk_236[4]), 236);
	zassert_equal(sizeof(vec_data_chunk_236), OWL_DATA_HDR_LEN + OWL_DATA_CHUNK_MAX);
	zassert_equal(OWL_DATA_HDR_LEN + OWL_DATA_CHUNK_MAX, OWL_ATT_PAYLOAD);
}

ZTEST_SUITE(proto, NULL, NULL, NULL, NULL, NULL);
