/* CONTROL parsing / response encoding and LIST paging, with fake ops. */

#include <string.h>
#include <zephyr/ztest.h>

#include "control.h"
#include "seg_index.h"
#include "vectors.h"

static struct {
	uint64_t unix_ms;
	int16_t tz;
	uint32_t id, offset, crc;
	int recording; /* -1 = not called */
	uint8_t cfg_key;
	uint32_t cfg_value;
	int aborts;
	int fetch_result;
	int ack_result;
} calls;

static struct owl_seg_slot slots[128];
static struct owl_seg_index ix;

static void f_time_sync(uint64_t unix_ms, int16_t tz, uint32_t *boot, uint64_t *up)
{
	calls.unix_ms = unix_ms;
	calls.tz = tz;
	*boot = 3;
	*up = 5000;
}

static int f_list(uint32_t after, struct owl_list_entry *out, int max)
{
	return owl_seg_index_list(&ix, after, out, max);
}

static int f_fetch(uint32_t id, uint32_t offset)
{
	calls.id = id;
	calls.offset = offset;
	return calls.fetch_result;
}

static int f_ack(uint32_t id, uint32_t crc)
{
	calls.id = id;
	calls.crc = crc;
	return calls.ack_result;
}

static void f_abort(void)
{
	calls.aborts++;
}

static int f_set_recording(bool on)
{
	calls.recording = on;
	return OWL_RESULT_OK;
}

static int f_set_config(uint8_t key, uint32_t value)
{
	calls.cfg_key = key;
	calls.cfg_value = value;
	return OWL_RESULT_OK;
}

static const struct owl_ctl_ops ops = {
	.time_sync = f_time_sync,
	.list = f_list,
	.fetch = f_fetch,
	.ack = f_ack,
	.abort = f_abort,
	.set_recording = f_set_recording,
	.set_config = f_set_config,
};

static uint8_t resp[OWL_ATT_PAYLOAD];

static size_t handle(const uint8_t *req, size_t len)
{
	return owl_control_handle(&ops, req, len, resp, sizeof(resp));
}

static void before(void *f)
{
	memset(&calls, 0, sizeof(calls));
	calls.recording = -1;
	owl_seg_index_init(&ix, slots, ARRAY_SIZE(slots));
}

ZTEST(control, test_time_sync)
{
	size_t n = handle(vec_req_time_sync, sizeof(vec_req_time_sync));

	zassert_equal(calls.unix_ms, 1700000000123ULL);
	zassert_equal(calls.tz, -540);
	zassert_equal(n, sizeof(vec_resp_time_sync));
	zassert_mem_equal(resp, vec_resp_time_sync, n);
}

ZTEST(control, test_fetch_and_ack_args)
{
	size_t n = handle(vec_req_fetch, sizeof(vec_req_fetch));

	zassert_equal(n, 3);
	zassert_equal(resp[0], OWL_OP_FETCH_SEGMENT | OWL_RESPONSE_BIT);
	zassert_equal(resp[1], 2, "seq echoed");
	zassert_equal(resp[2], OWL_RESULT_OK);
	zassert_equal(calls.id, 7);
	zassert_equal(calls.offset, 472);

	calls.ack_result = OWL_RESULT_CRC_MISMATCH;
	n = handle(vec_req_ack, sizeof(vec_req_ack));
	zassert_equal(n, 3);
	zassert_equal(resp[1], 3);
	zassert_equal(resp[2], OWL_RESULT_CRC_MISMATCH, "op result passed through");
	zassert_equal(calls.crc, 0x12345678);
}

ZTEST(control, test_set_recording)
{
	handle(vec_req_set_recording_on, sizeof(vec_req_set_recording_on));
	zassert_equal(calls.recording, 1);
	zassert_equal(resp[2], OWL_RESULT_OK);
	handle(vec_req_set_recording_off, sizeof(vec_req_set_recording_off));
	zassert_equal(calls.recording, 0);

	const uint8_t bad[] = {OWL_OP_SET_RECORDING, 6, 2};

	calls.recording = -1;
	handle(bad, sizeof(bad));
	zassert_equal(resp[2], OWL_RESULT_BAD_LENGTH);
	zassert_equal(calls.recording, -1, "invalid value must not reach the device");
}

ZTEST(control, test_set_config_and_abort)
{
	const uint8_t req[] = {OWL_OP_SET_CONFIG, 8, 2, 0x03, 0, 0, 0};
	const uint8_t ab[] = {OWL_OP_ABORT, 9};

	handle(req, sizeof(req));
	zassert_equal(calls.cfg_key, 2);
	zassert_equal(calls.cfg_value, 3);
	zassert_equal(handle(ab, sizeof(ab)), 3);
	zassert_equal(resp[0], OWL_OP_ABORT | OWL_RESPONSE_BIT);
	zassert_equal(calls.aborts, 1);
}

ZTEST(control, test_errors)
{
	const uint8_t unknown[] = {0x42, 17, 1, 2, 3};
	const uint8_t short_fetch[] = {OWL_OP_FETCH_SEGMENT, 18, 1, 0, 0, 0};
	const uint8_t only_op[] = {OWL_OP_LIST_SEGMENTS};

	zassert_equal(handle(unknown, sizeof(unknown)), 3);
	zassert_equal(resp[0], 0x42 | OWL_RESPONSE_BIT);
	zassert_equal(resp[1], 17);
	zassert_equal(resp[2], OWL_RESULT_UNKNOWN_OP);

	handle(short_fetch, sizeof(short_fetch));
	zassert_equal(resp[1], 18);
	zassert_equal(resp[2], OWL_RESULT_BAD_LENGTH);
	zassert_equal(calls.id, 0, "short request must not be executed");

	handle(only_op, sizeof(only_op));
	zassert_equal(resp[2], OWL_RESULT_BAD_LENGTH);

	zassert_equal(handle(only_op, 0), 0, "empty write gets no response");
}

ZTEST(control, test_longer_payload_is_accepted)
{
	/* §7: fields may be appended; old firmware ignores the tail. */
	uint8_t req[sizeof(vec_req_fetch) + 4];

	memcpy(req, vec_req_fetch, sizeof(vec_req_fetch));
	memset(&req[sizeof(vec_req_fetch)], 0xaa, 4);
	handle(req, sizeof(req));
	zassert_equal(resp[2], OWL_RESULT_OK);
	zassert_equal(calls.offset, 472);
}

static void fill_index(int n)
{
	for (int i = 1; i <= n; i++) {
		zassert_ok(owl_seg_index_put(&ix, i * 2, (i % 3) ? OWL_SEG_AUDIO : OWL_SEG_EVENTS,
					     1000 + i));
	}
}

ZTEST(control, test_list_paging)
{
	uint8_t req[] = {OWL_OP_LIST_SEGMENTS, 1, 0, 0, 0, 0, 26};
	uint32_t after = 0, seen = 0;
	int pages = 0;

	fill_index(60);
	for (;;) {
		owl_put_u32(&req[2], after);
		size_t n = handle(req, sizeof(req));

		zassert_equal(resp[2], OWL_RESULT_OK);
		int count = resp[3];

		zassert_equal(n, 4 + count * OWL_LIST_ENTRY_LEN);
		zassert_true(n <= OWL_ATT_PAYLOAD);
		if (count == 0) {
			break;
		}
		pages++;
		for (int i = 0; i < count; i++) {
			const uint8_t *e = &resp[4 + i * OWL_LIST_ENTRY_LEN];
			uint32_t id = owl_get_u32(e);

			zassert_true(id > after, "ascending and after after_id");
			zassert_equal(owl_get_u32(&e[5]), 1000 + id / 2);
			after = id;
			seen++;
		}
	}
	zassert_equal(seen, 60);
	zassert_equal(pages, 3, "26 + 26 + 8");
}

ZTEST(control, test_list_respects_max_and_mtu)
{
	uint8_t req[] = {OWL_OP_LIST_SEGMENTS, 1, 0, 0, 0, 0, 200};
	uint8_t small[20]; /* default MTU 23 -> 20 bytes of ATT payload */

	fill_index(60);
	handle(req, sizeof(req));
	zassert_equal(resp[3], OWL_LIST_MAX, "max above 26 is clamped");

	req[6] = 5;
	handle(req, sizeof(req));
	zassert_equal(resp[3], 5);

	req[6] = 0;
	handle(req, sizeof(req));
	zassert_equal(resp[3], 0);

	req[6] = 26;
	size_t n = owl_control_handle(&ops, req, sizeof(req), small, sizeof(small));

	zassert_equal(small[3], 1, "only one 9-byte entry fits in 20 bytes");
	zassert_equal(n, 13);
	zassert_equal(owl_control_list_capacity(OWL_ATT_PAYLOAD), 26);
}

ZTEST_SUITE(control, NULL, NULL, before, NULL, NULL);
