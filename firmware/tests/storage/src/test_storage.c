/*
 * storage.c on a simulated flash: segment bytes, ids across reboots, events,
 * delete-after-ACK, power-cut repair, full-flash pause/resume.
 */

#include <string.h>
#include <zephyr/ztest.h>
#include <zephyr/storage/flash_map.h>

#include "owl_proto.h"
#include "storage.h"

static bool mounted;
static uint8_t buf[64 * 1024];

/* Erase the partition and boot as a brand-new device. */
static void fresh(void)
{
	const struct flash_area *fa;

	if (mounted) {
		storage_test_power_cut();
	}
	zassert_ok(flash_area_open(PARTITION_ID(lfs_partition), &fa));
	zassert_ok(flash_area_erase(fa, 0, fa->fa_size));
	flash_area_close(fa);
	zassert_ok(storage_init());
	zassert_ok(storage_boot());
	mounted = true;
}

/* Power cut (nothing flushed) and boot again. */
static void power_cut_and_boot(void)
{
	storage_test_power_cut();
	zassert_ok(storage_init());
	zassert_ok(storage_boot());
}

static void clean_reboot(void)
{
	storage_flush();
	power_cut_and_boot();
}

static uint16_t frame_len(int i)
{
	return (uint16_t)(30 + (i * 7) % 40);
}

static void frame_fill(uint8_t *f, int i, uint16_t len)
{
	for (int k = 0; k < len; k++) {
		f[k] = (uint8_t)(i * 31 + k);
	}
}

/* Writes a closed audio segment of n frames; returns its id. */
static uint32_t write_segment(int n, uint64_t start_ms)
{
	uint8_t f[128];
	struct owl_list_entry e[OWL_LIST_MAX];

	zassert_ok(storage_seg_begin(OWL_CODEC_OPUS, 20, start_ms));
	for (int i = 0; i < n; i++) {
		uint16_t len = frame_len(i);

		frame_fill(f, i, len);
		zassert_ok(storage_seg_append(f, len));
	}
	zassert_ok(storage_seg_end());

	uint32_t last = 0;
	int got;

	while ((got = storage_list(last, e, OWL_LIST_MAX)) > 0) {
		last = e[got - 1].id;
	}
	return last;
}

/* Reads a whole segment the way FETCH does (236-byte chunks). */
static uint32_t read_all(uint32_t id, uint8_t *out, size_t max)
{
	uint32_t off = 0;
	int n;

	while ((n = storage_read(id, off, &out[off], MIN(OWL_DATA_CHUNK_MAX, max - off))) > 0) {
		off += n;
	}
	zassert_true(n == 0, "read error %d", n);
	return off;
}

/* Checks every [len][frame] of an audio segment; returns the frame count. */
static int check_frames(const uint8_t *seg, uint32_t size)
{
	uint32_t pos = OWL_SEG_HDR_LEN;
	int i = 0;
	uint8_t f[128];

	while (pos < size) {
		zassert_true(pos + 2 <= size, "partial length field at %u", pos);
		uint16_t len = owl_get_u16(&seg[pos]);

		zassert_equal(len, frame_len(i), "frame %d length", i);
		zassert_true(pos + 2 + len <= size, "partial frame %d", i);
		frame_fill(f, i, len);
		zassert_mem_equal(&seg[pos + 2], f, len, "frame %d bytes", i);
		pos += 2 + len;
		i++;
	}
	zassert_equal(pos, size);
	return i;
}

ZTEST(storage, test_boot_count_and_monotonic_ids)
{
	struct owl_state st;

	fresh();
	storage_get_state(&st);
	zassert_equal(st.boot_count, 1);
	uint32_t a = write_segment(3, 100);

	zassert_equal(a, 1, "first id is 1");
	clean_reboot();
	storage_get_state(&st);
	zassert_equal(st.boot_count, 2);
	uint32_t b = write_segment(3, 100);

	zassert_true(b > a);
	/* Even if the id of a deleted segment was the highest, it is not reused. */
	zassert_ok(storage_delete(b));
	power_cut_and_boot();
	zassert_true(write_segment(3, 100) > b);
	storage_get_state(&st);
	zassert_equal(st.boot_count, 3);
}

ZTEST(storage, test_segment_bytes_match_protocol)
{
	struct owl_seg_header h;
	struct owl_list_entry e;
	uint32_t crc;

	fresh();
	uint32_t id = write_segment(300, 123456);

	zassert_true(storage_seg_info(id, &e));
	zassert_equal(e.type, OWL_SEG_AUDIO);

	uint32_t size = read_all(id, buf, sizeof(buf));

	zassert_equal(size, e.size);
	zassert_ok(owl_seg_header_unpack(buf, &h));
	zassert_equal(h.version, 1);
	zassert_equal(h.type, OWL_SEG_AUDIO);
	zassert_equal(h.codec, OWL_CODEC_OPUS);
	zassert_equal(h.frame_ms, 20);
	zassert_equal(h.id, id);
	zassert_equal(h.boot_count, 1);
	zassert_equal(h.start_uptime_ms, 123456);
	zassert_equal(h.sample_rate_div100, 160);
	zassert_equal(h.channels, 1);
	zassert_equal(h.payload_len, size - OWL_SEG_HDR_LEN, "payload_len filled in on read");
	zassert_equal(check_frames(buf, size), 300);

	zassert_ok(storage_crc(id, &crc));
	zassert_equal(crc, owl_crc32(0, buf, size));

	/* A resumed FETCH that starts inside payload_len still sees the real value. */
	uint8_t part[8];

	zassert_equal(storage_read(id, 26, part, sizeof(part)), sizeof(part));
	zassert_mem_equal(part, &buf[26], sizeof(part));
	zassert_equal(storage_read(id, size, part, 1), 0, "offset == size: end");
	zassert_equal(storage_read(9999, 0, part, 1), -ENOENT);
}

ZTEST(storage, test_events_written_and_rotated)
{
	struct owl_list_entry e[4];
	struct storage_usage u;

	fresh();
	zassert_ok(storage_event(OWL_EVT_BOOKMARK, 0));
	zassert_ok(storage_event(OWL_EVT_RECORDING_OFF, OWL_CAUSE_BUTTON));
	zassert_ok(storage_event(OWL_EVT_RECORDING_ON, OWL_CAUSE_APP));

	storage_get_usage(&u);
	zassert_equal(u.pending, 1, "open event segment counts as pending");
	zassert_equal(storage_list(0, e, 4), 0, "not listable while open");

	zassert_ok(storage_events_rotate());
	zassert_equal(storage_list(0, e, 4), 1);
	zassert_equal(e[0].type, OWL_SEG_EVENTS);
	zassert_equal(e[0].size, OWL_SEG_HDR_LEN + 3 * OWL_EVENT_REC_LEN);

	uint32_t size = read_all(e[0].id, buf, sizeof(buf));
	const uint8_t *r = &buf[OWL_SEG_HDR_LEN];

	zassert_equal(buf[5], OWL_SEG_EVENTS);
	zassert_equal(owl_get_u32(&buf[28]), size - OWL_SEG_HDR_LEN);
	zassert_equal(r[8], OWL_EVT_BOOKMARK);
	zassert_equal(r[10 + 8], OWL_EVT_RECORDING_OFF);
	zassert_equal(r[10 + 9], OWL_CAUSE_BUTTON);
	zassert_equal(r[20 + 8], OWL_EVT_RECORDING_ON);
	zassert_equal(r[20 + 9], OWL_CAUSE_APP);
	zassert_true(owl_get_u64(&r[20]) >= owl_get_u64(&r[0]), "uptimes ascend");

	zassert_ok(storage_events_rotate(), "rotating an empty log is a no-op");
	zassert_equal(storage_list(0, e, 4), 1);
}

ZTEST(storage, test_delete_only_what_is_asked)
{
	struct owl_list_entry e[4];

	fresh();
	uint32_t a = write_segment(5, 0);
	uint32_t b = write_segment(5, 0);

	zassert_ok(storage_delete(a));
	zassert_equal(storage_delete(a), -ENOENT);
	zassert_equal(storage_list(0, e, 4), 1);
	zassert_equal(e[0].id, b);
	power_cut_and_boot();
	zassert_equal(storage_list(0, e, 4), 1, "deletion is durable, b survives");
	zassert_equal(e[0].id, b);
}

ZTEST(storage, test_power_cut_keeps_whole_frames)
{
	struct owl_list_entry e[4];
	uint8_t f[128];
	uint32_t written = OWL_SEG_HDR_LEN;
	int frames = 0;

	fresh();
	zassert_ok(storage_seg_begin(OWL_CODEC_OPUS, 20, 0));
	/* ~10 KiB: two 4 KiB blocks reach flash (synced), the rest is in RAM. */
	while (written < 10 * 1024) {
		uint16_t len = frame_len(frames);

		frame_fill(f, frames, len);
		zassert_ok(storage_seg_append(f, len));
		written += 2 + len;
		frames++;
	}
	zassert_ok(storage_event(OWL_EVT_BOOKMARK, 0));
	zassert_ok(storage_event(OWL_EVT_BOOKMARK, 0));

	power_cut_and_boot();

	zassert_equal(storage_list(0, e, 4), 2, "audio and event segments recovered");
	zassert_equal(e[0].type, OWL_SEG_AUDIO);
	zassert_true(e[0].size >= 8 * 1024 - 128 && e[0].size <= written,
		     "synced blocks kept (%u of %u)", e[0].size, written);
	uint32_t size = read_all(e[0].id, buf, sizeof(buf));
	int kept = check_frames(buf, size);

	zassert_true(kept > 0 && kept < frames, "a prefix of whole frames (%d of %d)", kept, frames);

	zassert_equal(e[1].type, OWL_SEG_EVENTS);
	zassert_equal(e[1].size, OWL_SEG_HDR_LEN + 2 * OWL_EVENT_REC_LEN, "each event was synced");

	uint32_t next = write_segment(3, 0);

	zassert_true(next > e[1].id, "no id reuse after a crash");
}

ZTEST(storage, test_empty_segment_is_dropped)
{
	struct owl_list_entry e[2];

	fresh();
	zassert_ok(storage_seg_begin(OWL_CODEC_OPUS, 20, 0));
	zassert_ok(storage_seg_end());
	zassert_equal(storage_list(0, e, 2), 0);
	zassert_ok(storage_seg_begin(OWL_CODEC_OPUS, 20, 0));
	power_cut_and_boot();
	zassert_equal(storage_list(0, e, 2), 0, "header-only leftover is removed");
}

ZTEST(storage, test_state_survives_reboot)
{
	struct owl_state st;

	fresh();
	zassert_ok(storage_set_recording(false));
	zassert_equal(storage_set_config(OWL_CFG_SEGMENT_MAX_S, 60), OWL_RESULT_OK);
	zassert_equal(storage_set_config(OWL_CFG_VAD_SENSITIVITY, 9), OWL_RESULT_BAD_LENGTH);
	zassert_ok(storage_set_power_state(OWL_PWR_OFF_BUTTON));

	/* System OFF and wake: storage_init only, the power-on gate has not passed yet. */
	storage_test_power_cut();
	zassert_ok(storage_init());
	storage_get_state(&st);
	zassert_equal(st.power_state, OWL_PWR_OFF_BUTTON);
	zassert_false(st.recording_on, "recording stays off (decisions #14)");
	zassert_equal(st.cfg.segment_max_s, 60);
	zassert_equal(st.cfg.vad_sensitivity, 2);
	zassert_equal(st.boot_count, 1, "not counted until we really power on");

	zassert_ok(storage_boot());
	storage_get_state(&st);
	zassert_equal(st.power_state, OWL_PWR_ON);
	zassert_equal(st.boot_count, 2);
}

ZTEST(storage, test_full_pauses_and_resumes)
{
	struct storage_usage u;
	uint32_t ids[32];
	int nseg = 0;
	uint8_t f[200];
	int err = 0;

	fresh();
	memset(f, 0x5a, sizeof(f));
	/* ~80 KiB segments until the 128 KiB reserve is reached. */
	while (nseg < ARRAY_SIZE(ids)) {
		err = storage_seg_begin(OWL_CODEC_OPUS, 20, 0);
		if (err) {
			break;
		}
		for (int i = 0; i < 400 && !err; i++) {
			err = storage_seg_append(f, sizeof(f));
		}
		storage_seg_end();
		struct owl_list_entry e[OWL_LIST_MAX];
		int n = storage_list(nseg ? ids[nseg - 1] : 0, e, OWL_LIST_MAX);

		if (n > 0) {
			ids[nseg++] = e[n - 1].id;
		}
		if (err) {
			break;
		}
	}
	zassert_equal(err, -ENOSPC, "writer is told the flash is full");
	storage_get_usage(&u);
	zassert_true(u.full);
	zassert_true(u.low, ">= 90 %% used");
	zassert_equal(storage_seg_begin(OWL_CODEC_OPUS, 20, 0), -ENOSPC);
	zassert_ok(storage_event(OWL_EVT_STORAGE_FULL, 0), "events still fit in the reserve");

	/* The phone ACKs one segment: not enough to resume (hysteresis). */
	zassert_ok(storage_delete(ids[0]));
	storage_get_usage(&u);
	zassert_true(u.full, "still paused just above the reserve");

	for (int i = 1; i < 5; i++) {
		zassert_ok(storage_delete(ids[i]));
	}
	storage_get_usage(&u);
	zassert_false(u.full, "resumes with room to spare");
	zassert_ok(storage_seg_begin(OWL_CODEC_OPUS, 20, 0));
	zassert_ok(storage_seg_end());
	zassert_true(nseg > 5);
}

ZTEST_SUITE(storage, NULL, NULL, NULL, NULL, NULL);
