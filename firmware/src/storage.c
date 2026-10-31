/*
 * LittleFS storage for segments, events and the persisted state. See storage.h.
 *
 * Two LittleFS facts drive the design:
 *   - Appending is cheap, but writing into the middle of a file rewrites every
 *     block after it (files are back-linked block lists). So the header's
 *     payload_len is NOT patched when a segment closes; on flash it stays
 *     0xFFFFFFFF and storage_read() fills in the real value (size - 32) on the
 *     way out. Every byte the phone receives is still exactly protocol §3.
 *   - Nothing written is durable until fs_sync()/fs_close(). Audio goes to the
 *     file in 4 KiB writes and is synced every CONFIG_OWL_SEG_SYNC_KB (~8 s of
 *     Opus). Each sync costs a copy of the partly filled last block (file blocks
 *     start with skip-list pointers, so writes never end block-aligned), which
 *     is why we don't sync every write. Events are synced one by one
 *     (decisions #19).
 *   - fs_statvfs() walks the whole file system, so it runs rarely; in between,
 *     usage is estimated, and published in a spinlock-protected copy that
 *     audio and the BT RX thread read without waiting for the flash.
 *
 * File names carry id and type so boot does not have to open every file:
 *   seg/0000002a.1    closed audio segment id 0x2a
 *   seg/0000002b.2    closed event segment
 *   seg/0000002c.1.w  still being written; after a crash it is repaired at boot
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/fs/fs.h>
#include <zephyr/fs/littlefs.h>
#include <zephyr/logging/log.h>

#include "seg_index.h"
#include "storage.h"

LOG_MODULE_REGISTER(storage, CONFIG_OWL_LOG_LEVEL);

FS_FSTAB_DECLARE_ENTRY(DT_NODELABEL(owl_lfs));

#define MNT        "/lfs"
#define SEG_DIR    MNT "/seg"
#define STATE_PATH MNT "/state"
#define PATH_MAX_LEN 32

#define BLOCK      4096
#define WRITE_BUF  BLOCK
/* Events per event segment before starting a new one (keeps fetches small). */
#define EVENTS_PER_SEGMENT 512

/* Stop recording below this much free space; resume above RESUME. */
#define RESERVE_BYTES ((uint64_t)CONFIG_OWL_STORAGE_RESERVE_KB * 1024U)
#define RESUME_BYTES  (RESERVE_BYTES + 256U * 1024U)

static K_MUTEX_DEFINE(lock);

static struct owl_state st;
static struct owl_seg_slot slots[CONFIG_OWL_SEG_INDEX_MAX];
static struct owl_seg_index ix;

static struct {
	bool open;
	struct fs_file_t f;
	uint32_t id;
	bool failed; /* a write failed: refuse appends until the segment is ended */
	size_t fill;
	uint32_t unsynced;
	uint8_t buf[WRITE_BUF];
} aw; /* audio writer */

static struct {
	bool open;
	struct fs_file_t f;
	uint32_t id;
	uint32_t count;
} ew; /* event writer */

static struct {
	bool open;
	struct fs_file_t f;
	uint32_t id;
	uint32_t size;
} rd; /* cached reader for FETCH */

static struct {
	uint64_t total;
	uint64_t free_at_stat;
	uint64_t written_since_stat;
	uint64_t freed_since_stat;
	uint32_t changes_since_stat;
	bool full;
} space;

/* Lock-free copy for readers that must not wait on the flash (BT RX, audio). */
static struct k_spinlock pub_lock;
static struct storage_usage pub;

static storage_space_cb_t space_cb;

/* ---------------------------------------------------------------- helpers */

static void seg_path(char *out, uint32_t id, uint8_t type, bool writing)
{
	snprintf(out, PATH_MAX_LEN, SEG_DIR "/%08x.%u%s", (unsigned int)id, type,
		 writing ? ".w" : "");
}

static int state_save_locked(void)
{
	struct fs_file_t f;
	uint8_t blob[OWL_STATE_BLOB_LEN];
	int err;

	owl_state_pack(&st, blob);
	fs_file_t_init(&f);
	err = fs_open(&f, STATE_PATH, FS_O_CREATE | FS_O_WRITE | FS_O_TRUNC);
	if (err) {
		LOG_ERR("state open: %d", err);
		return err;
	}
	err = fs_write(&f, blob, sizeof(blob));
	/* close = commit; LittleFS keeps the old copy until this succeeds */
	int cerr = fs_close(&f);

	space.written_since_stat += sizeof(blob);
	if (err < 0) {
		return err;
	}
	return cerr;
}

static void state_load_locked(void)
{
	struct fs_file_t f;
	uint8_t blob[OWL_STATE_BLOB_LEN];
	int n = -1;

	fs_file_t_init(&f);
	if (fs_open(&f, STATE_PATH, FS_O_READ) == 0) {
		n = fs_read(&f, blob, sizeof(blob));
		fs_close(&f);
	}
	if (owl_state_unpack(blob, n, &st) != 0) {
		LOG_WRN("no valid state file, using defaults");
	}
}

static void refresh_space_locked(void)
{
	struct fs_statvfs sv;

	if (fs_statvfs(MNT, &sv) == 0) {
		space.total = (uint64_t)sv.f_blocks * sv.f_frsize;
		space.free_at_stat = (uint64_t)sv.f_bfree * sv.f_frsize;
		space.written_since_stat = 0;
		space.freed_since_stat = 0;
		space.changes_since_stat = 0;
	}
}

/* Exact numbers now and then; cheap estimates in between (statvfs walks the FS). */
static void maybe_refresh_space_locked(void)
{
	if (++space.changes_since_stat >= 16) {
		refresh_space_locked();
	}
}

static uint64_t free_estimate_locked(void)
{
	uint64_t free = space.free_at_stat + space.freed_since_stat;
	uint64_t used = space.written_since_stat + aw.fill;

	free = MIN(free, space.total);
	return free > used ? free - used : 0;
}

/* Updates the full flag with hysteresis. Returns true if full. */
static bool check_full_locked(void)
{
	uint64_t free = free_estimate_locked();
	bool index_full = ix.count >= ix.cap - 1;

	if (!space.full && (free < RESERVE_BYTES || index_full)) {
		space.full = true;
		LOG_WRN("storage full (free %u KiB, %d segments)", (unsigned int)(free / 1024),
			ix.count);
	} else if (space.full && free >= RESUME_BYTES && !index_full) {
		space.full = false;
		LOG_INF("storage has space again (free %u KiB)", (unsigned int)(free / 1024));
	}

	uint64_t used = space.total > free ? space.total - free : 0;
	struct storage_usage u = {
		.total_kb = (uint32_t)(space.total / 1024),
		.used_kb = (uint32_t)(used / 1024),
		.pending = (uint16_t)MIN(ix.count + ((ew.open && ew.count) ? 1 : 0), UINT16_MAX),
		.low = space.total && used * 10 >= space.total * 9,
		.full = space.full,
	};
	k_spinlock_key_t key = k_spin_lock(&pub_lock);

	pub = u;
	k_spin_unlock(&pub_lock, key);
	return space.full;
}

/* LittleFS itself ran out: trust it over our estimate until space is freed. */
static void note_enospc_locked(int err)
{
	if (err == -ENOSPC) {
		space.free_at_stat = 0;
		space.freed_since_stat = 0;
		space.written_since_stat = 0;
		check_full_locked();
	}
}

static void reader_close_locked(void)
{
	if (rd.open) {
		fs_close(&rd.f);
		rd.open = false;
	}
}

/* ------------------------------------------------------------- recovery */

/*
 * A ".w" file was being written when power was lost or the chip reset. Keep
 * everything that is whole: complete [len][frame] records for audio, complete
 * 10-byte records for events. Then rename it to its closed name.
 */
static int repair_locked(const char *name, uint32_t id, uint8_t type, uint32_t size)
{
	char path[PATH_MAX_LEN];
	char final[PATH_MAX_LEN];
	struct fs_file_t f;
	uint32_t good = OWL_SEG_HDR_LEN;
	int err;

	snprintf(path, sizeof(path), SEG_DIR "/%.15s", name);
	seg_path(final, id, type, false);
	fs_file_t_init(&f);
	err = fs_open(&f, path, FS_O_RDWR);
	if (err) {
		return err;
	}

	uint8_t hdr[OWL_SEG_HDR_LEN];
	struct owl_seg_header h;

	if (size < OWL_SEG_HDR_LEN || fs_read(&f, hdr, sizeof(hdr)) != sizeof(hdr) ||
	    owl_seg_header_unpack(hdr, &h) != 0 || h.id != id) {
		good = 0;
	} else if (type == OWL_SEG_AUDIO) {
		uint8_t lenb[2];

		while (good + 2 <= size) {
			if (fs_seek(&f, good, FS_SEEK_SET) || fs_read(&f, lenb, 2) != 2) {
				break;
			}
			uint32_t next = good + 2 + owl_get_u16(lenb);

			if (next > size) {
				break;
			}
			good = next;
		}
	} else {
		good = OWL_SEG_HDR_LEN +
		       (size - OWL_SEG_HDR_LEN) / OWL_EVENT_REC_LEN * OWL_EVENT_REC_LEN;
	}

	if (good > OWL_SEG_HDR_LEN && good < size) {
		/* Shrinking is cheap in LittleFS: it just points at an earlier block. */
		err = fs_truncate(&f, good);
	}
	fs_close(&f);

	if (err) {
		LOG_ERR("recover %s: truncate failed (%d), retry next boot", name, err);
		return err;
	}
	if (good <= OWL_SEG_HDR_LEN) {
		LOG_WRN("recover %s: nothing usable, deleting", name);
		fs_unlink(path);
		return 0;
	}
	err = fs_rename(path, final);
	if (err) {
		return err;
	}
	LOG_INF("recovered segment %u: %u -> %u bytes", (unsigned int)id, (unsigned int)size,
		(unsigned int)good);
	return owl_seg_index_put(&ix, id, type, good) ? -ENOMEM : 0;
}

/* Parses "XXXXXXXX.T" or "XXXXXXXX.T.w". Returns 0 on success. */
static int parse_name(const char *name, uint32_t *id, uint8_t *type, bool *writing)
{
	char *end;

	if (strlen(name) < 10 || name[8] != '.') {
		return -1;
	}
	*id = (uint32_t)strtoul(name, &end, 16);
	if (end != name + 8) {
		return -1;
	}
	*type = (uint8_t)(name[9] - '0');
	if (*type != OWL_SEG_AUDIO && *type != OWL_SEG_EVENTS) {
		return -1;
	}
	*writing = strcmp(&name[10], ".w") == 0;
	return (*writing || name[10] == '\0') ? 0 : -1;
}

static int scan_locked(void)
{
	struct fs_dir_t dir;
	struct fs_dirent ent;
	uint32_t max_id = 0;
	int recovered = 0;
	int err;

	fs_dir_t_init(&dir);
	err = fs_opendir(&dir, SEG_DIR);
	if (err) {
		return err;
	}

	/*
	 * Collect closed segments first. Repairs rename entries, which must not
	 * happen while iterating, so remember up to a few and handle them after.
	 */
	struct {
		char name[16];
		uint32_t id;
		uint8_t type;
		uint32_t size;
	} todo[4];
	int ntodo = 0;

	while (fs_readdir(&dir, &ent) == 0 && ent.name[0] != '\0') {
		uint32_t id;
		uint8_t type;
		bool writing;

		if (ent.type != FS_DIR_ENTRY_FILE || parse_name(ent.name, &id, &type, &writing)) {
			LOG_WRN("ignoring %s", ent.name);
			continue;
		}
		max_id = MAX(max_id, id);
		if (writing) {
			if (ntodo < ARRAY_SIZE(todo)) {
				strncpy(todo[ntodo].name, ent.name, sizeof(todo[0].name) - 1);
				todo[ntodo].name[sizeof(todo[0].name) - 1] = '\0';
				todo[ntodo].id = id;
				todo[ntodo].type = type;
				todo[ntodo].size = (uint32_t)ent.size;
				ntodo++;
			}
			continue;
		}
		if (owl_seg_index_put(&ix, id, type, (uint32_t)ent.size)) {
			LOG_ERR("segment index full at id %u", (unsigned int)id);
		}
	}
	fs_closedir(&dir);

	for (int i = 0; i < ntodo; i++) {
		if (repair_locked(todo[i].name, todo[i].id, todo[i].type, todo[i].size) == 0) {
			recovered++;
		}
	}

	if (st.next_seg_id <= max_id) {
		LOG_WRN("next id %u behind files (%u), skipping ahead",
			(unsigned int)st.next_seg_id, (unsigned int)max_id);
		st.next_seg_id = max_id + 1;
	}
	LOG_INF("%d segments on flash (%d recovered), next id %u", ix.count, recovered,
		(unsigned int)st.next_seg_id);
	return 0;
}

/* ---------------------------------------------------------------- public */

int storage_init(void)
{
	struct fs_mount_t *mp = &FS_FSTAB_ENTRY(DT_NODELABEL(owl_lfs));
	int err;

	owl_seg_index_init(&ix, slots, ARRAY_SIZE(slots));
	fs_file_t_init(&aw.f);
	fs_file_t_init(&ew.f);
	fs_file_t_init(&rd.f);

	/* Formats the partition on the very first mount (blank flash). */
	err = fs_mount(mp);
	if (err) {
		LOG_ERR("mount %s failed: %d", mp->mnt_point, err);
		return err;
	}

	k_mutex_lock(&lock, K_FOREVER);
	err = fs_mkdir(SEG_DIR);
	if (err && err != -EEXIST) {
		LOG_ERR("mkdir: %d", err);
	}
	state_load_locked();
	refresh_space_locked();
	check_full_locked();
	k_mutex_unlock(&lock);

	LOG_INF("mounted: %u / %u KiB free, boot_count %u",
		(unsigned int)(space.free_at_stat / 1024), (unsigned int)(space.total / 1024),
		(unsigned int)st.boot_count);
	return 0;
}

int storage_boot(void)
{
	int err;

	k_mutex_lock(&lock, K_FOREVER);
	st.boot_count++;
	st.power_state = OWL_PWR_ON;
	err = scan_locked();
	if (!err) {
		err = state_save_locked();
	}
	refresh_space_locked();
	check_full_locked();
	k_mutex_unlock(&lock);
	return err;
}

void storage_get_state(struct owl_state *out)
{
	k_mutex_lock(&lock, K_FOREVER);
	*out = st;
	k_mutex_unlock(&lock);
}

uint32_t storage_boot_count(void)
{
	return st.boot_count; /* word read: atomic, and only changes before threads start */
}

int storage_set_recording(bool on)
{
	int err = 0;

	k_mutex_lock(&lock, K_FOREVER);
	if (st.recording_on != on) {
		st.recording_on = on;
		err = state_save_locked();
	}
	k_mutex_unlock(&lock);
	return err;
}

int storage_set_power_state(uint8_t power_state)
{
	int err;

	k_mutex_lock(&lock, K_FOREVER);
	st.power_state = power_state;
	err = state_save_locked();
	k_mutex_unlock(&lock);
	return err;
}

int storage_set_config(uint8_t key, uint32_t value)
{
	int res;

	k_mutex_lock(&lock, K_FOREVER);
	res = owl_config_set(&st.cfg, key, value);
	if (res == OWL_RESULT_OK && state_save_locked() != 0) {
		res = OWL_RESULT_BUSY;
	}
	k_mutex_unlock(&lock);
	return res;
}

/* Allocates the next id and persists it before any file uses it; 0 if that failed
 * (an unpersisted id could be handed out again after a reboot).
 */
static uint32_t alloc_id_locked(void)
{
	uint32_t id = st.next_seg_id++;
	int err = state_save_locked();

	if (err) {
		LOG_ERR("cannot persist next id: %d", err);
		note_enospc_locked(err);
		return 0;
	}
	return id;
}

static int open_segment_locked(struct fs_file_t *f, uint32_t id, uint8_t type, uint8_t codec,
			       uint8_t frame_ms, uint64_t start_uptime_ms, uint8_t hdr[32])
{
	char path[PATH_MAX_LEN];
	struct owl_seg_header h;
	int err;

	owl_seg_header_init(&h, type, codec, frame_ms, id, st.boot_count, start_uptime_ms);
	h.payload_len = UINT32_MAX; /* filled in by storage_read(), see top of file */
	owl_seg_header_pack(&h, hdr);

	seg_path(path, id, type, true);
	fs_file_t_init(f);
	err = fs_open(f, path, FS_O_CREATE | FS_O_WRITE | FS_O_TRUNC);
	if (err) {
		LOG_ERR("open %s: %d", path, err);
	}
	return err;
}

static int close_segment_locked(struct fs_file_t *f, uint32_t id, uint8_t type)
{
	char from[PATH_MAX_LEN], to[PATH_MAX_LEN];
	struct fs_dirent ent;
	int err;

	err = fs_close(f);
	seg_path(from, id, type, true);
	seg_path(to, id, type, false);
	if (err) {
		LOG_ERR("close %u: %d", (unsigned int)id, err);
		return err; /* the .w file is repaired at next boot */
	}
	err = fs_stat(from, &ent);
	if (err || ent.size <= OWL_SEG_HDR_LEN) {
		fs_unlink(from);
		return err;
	}
	err = fs_rename(from, to);
	if (err) {
		LOG_ERR("rename %u: %d", (unsigned int)id, err);
		return err;
	}
	if (owl_seg_index_put(&ix, id, type, (uint32_t)ent.size)) {
		LOG_ERR("index full, segment %u only visible after reboot", (unsigned int)id);
	}
	return 0;
}

int storage_seg_begin(uint8_t codec, uint8_t frame_ms, uint64_t start_uptime_ms)
{
	int err;

	k_mutex_lock(&lock, K_FOREVER);
	if (aw.open) {
		/* The END for it got lost: close it rather than wedge the recorder. */
		LOG_WRN("segment %u still open, closing it", (unsigned int)aw.id);
		k_mutex_unlock(&lock);
		storage_seg_end();
		k_mutex_lock(&lock, K_FOREVER);
	}
	if (check_full_locked()) {
		err = -ENOSPC;
		goto out;
	}
	aw.id = alloc_id_locked();
	if (aw.id == 0) {
		err = space.full ? -ENOSPC : -EIO;
		goto out;
	}
	err = open_segment_locked(&aw.f, aw.id, OWL_SEG_AUDIO, codec, frame_ms,
				  start_uptime_ms, aw.buf);
	note_enospc_locked(err);
	if (!err) {
		aw.open = true;
		aw.failed = false;
		aw.unsynced = 0;
		aw.fill = OWL_SEG_HDR_LEN;
		LOG_DBG("segment %u begins at %u ms", (unsigned int)aw.id,
			(unsigned int)start_uptime_ms);
	}
out:
	k_mutex_unlock(&lock);
	return err;
}

/* Writes the RAM buffer to the file; syncs when enough is unsynced or `sync`. */
static int flush_audio_locked(bool sync)
{
	int err = 0;

	if (aw.fill > 0) {
		ssize_t n = fs_write(&aw.f, aw.buf, aw.fill);

		if (n != (ssize_t)aw.fill) {
			err = n < 0 ? (int)n : -ENOSPC; /* short write = out of space */
		} else {
			space.written_since_stat += aw.fill;
			aw.unsynced += aw.fill;
		}
		/* Never leave a full buffer behind: the next append would overrun it. */
		aw.fill = 0;
	}
	if (!err && (sync || aw.unsynced >= CONFIG_OWL_SEG_SYNC_KB * 1024U)) {
		err = fs_sync(&aw.f);
		aw.unsynced = 0;
	}
	if (err) {
		LOG_ERR("segment %u write: %d", (unsigned int)aw.id, err);
		aw.failed = true;
		note_enospc_locked(err);
	}
	return err;
}

int storage_seg_append(const void *frame, uint16_t len)
{
	const uint8_t *src = frame;
	uint8_t lenb[2];
	int err = 0;

	k_mutex_lock(&lock, K_FOREVER);
	if (!aw.open || aw.failed) {
		err = -EINVAL;
		goto out;
	}
	if (check_full_locked()) {
		err = -ENOSPC;
		goto out;
	}

	owl_put_u16(lenb, len);
	/* Copy [len][frame] into the block buffer; write a whole block when it fills. */
	for (size_t i = 0; i < 2U + len && !err; i++) {
		aw.buf[aw.fill++] = i < 2 ? lenb[i] : src[i - 2];
		if (aw.fill == WRITE_BUF) {
			err = flush_audio_locked(false);
			check_full_locked(); /* publish the new estimate */
		}
	}
out:
	k_mutex_unlock(&lock);
	return err;
}

int storage_seg_end(void)
{
	int err;

	k_mutex_lock(&lock, K_FOREVER);
	if (!aw.open) {
		k_mutex_unlock(&lock);
		return 0;
	}
	err = aw.failed ? 0 : flush_audio_locked(true);
	aw.open = false;
	aw.failed = false;
	int cerr = close_segment_locked(&aw.f, aw.id, OWL_SEG_AUDIO);

	maybe_refresh_space_locked();
	check_full_locked();
	LOG_INF("segment %u closed", (unsigned int)aw.id);
	k_mutex_unlock(&lock);
	return err ? err : cerr;
}

static int events_close_locked(void)
{
	if (!ew.open) {
		return 0;
	}
	ew.open = false;
	int err = close_segment_locked(&ew.f, ew.id, OWL_SEG_EVENTS);

	LOG_INF("event segment %u closed (%u events)", (unsigned int)ew.id,
		(unsigned int)ew.count);
	return err;
}

int storage_event(uint8_t event, uint8_t arg)
{
	uint8_t rec[OWL_EVENT_REC_LEN];
	uint8_t hdr[OWL_SEG_HDR_LEN];
	uint64_t now = (uint64_t)k_uptime_get();
	int err = 0;

	owl_event_pack(now, event, arg, rec);

	k_mutex_lock(&lock, K_FOREVER);
	if (!ew.open) {
		/* Events are tiny and always kept, even when audio is paused for space. */
		ew.id = alloc_id_locked();
		if (ew.id == 0) {
			err = -EIO;
			goto out;
		}
		err = open_segment_locked(&ew.f, ew.id, OWL_SEG_EVENTS, OWL_CODEC_PCM16, 0, now,
					  hdr);
		if (!err && fs_write(&ew.f, hdr, sizeof(hdr)) != sizeof(hdr)) {
			err = -EIO;
		}
		if (err) {
			goto out;
		}
		ew.open = true;
		ew.count = 0;
	}
	if (fs_write(&ew.f, rec, sizeof(rec)) != sizeof(rec)) {
		err = -EIO;
		goto out;
	}
	err = fs_sync(&ew.f);
	ew.count++;
	space.written_since_stat += sizeof(rec);
	if (ew.count >= EVENTS_PER_SEGMENT) {
		events_close_locked();
	}
	check_full_locked();
out:
	k_mutex_unlock(&lock);
	LOG_INF("event %u arg %u at %u ms%s", event, arg, (unsigned int)now, err ? " FAILED" : "");
	return err;
}

int storage_events_rotate(void)
{
	int err = 0;

	k_mutex_lock(&lock, K_FOREVER);
	if (ew.open && ew.count > 0) {
		err = events_close_locked();
		check_full_locked();
	}
	k_mutex_unlock(&lock);
	return err;
}

int storage_list(uint32_t after_id, struct owl_list_entry *out, int max)
{
	int n;

	k_mutex_lock(&lock, K_FOREVER);
	n = owl_seg_index_list(&ix, after_id, out, max);
	k_mutex_unlock(&lock);
	return n;
}

bool storage_seg_info(uint32_t id, struct owl_list_entry *out)
{
	bool ok;

	k_mutex_lock(&lock, K_FOREVER);
	ok = owl_seg_index_get(&ix, id, out);
	k_mutex_unlock(&lock);
	return ok;
}

int storage_read(uint32_t id, uint32_t offset, void *buf, size_t len)
{
	struct owl_list_entry e;
	int n;

	k_mutex_lock(&lock, K_FOREVER);
	if (!owl_seg_index_get(&ix, id, &e)) {
		n = -ENOENT;
		goto out;
	}
	if (offset >= e.size) {
		n = 0;
		goto out;
	}
	len = MIN(len, e.size - offset);

	if (!rd.open || rd.id != id) {
		char path[PATH_MAX_LEN];

		reader_close_locked();
		seg_path(path, id, e.type, false);
		fs_file_t_init(&rd.f);
		n = fs_open(&rd.f, path, FS_O_READ);
		if (n) {
			goto out;
		}
		rd.open = true;
		rd.id = id;
		rd.size = e.size;
	}
	n = fs_seek(&rd.f, offset, FS_SEEK_SET);
	if (n == 0) {
		n = (int)fs_read(&rd.f, buf, len);
	}

	/* Fill in payload_len (header bytes 28..31) where this read overlaps it. */
	if (n > 0 && offset < OWL_SEG_HDR_LEN) {
		uint8_t plen[4];

		owl_put_u32(plen, e.size - OWL_SEG_HDR_LEN);
		for (int i = 0; i < 4; i++) {
			uint32_t at = 28U + i;

			if (at >= offset && at < offset + (uint32_t)n) {
				((uint8_t *)buf)[at - offset] = plen[i];
			}
		}
	}
out:
	k_mutex_unlock(&lock);
	return n;
}

int storage_crc(uint32_t id, uint32_t *crc)
{
	uint8_t buf[256];
	uint32_t off = 0, c = 0;
	int n;

	while ((n = storage_read(id, off, buf, sizeof(buf))) > 0) {
		c = owl_crc32(c, buf, n);
		off += n;
	}
	if (n < 0) {
		return n;
	}
	*crc = c;
	return 0;
}

int storage_delete(uint32_t id)
{
	struct owl_list_entry e;
	char path[PATH_MAX_LEN];
	int err;

	k_mutex_lock(&lock, K_FOREVER);
	if (!owl_seg_index_get(&ix, id, &e)) {
		k_mutex_unlock(&lock);
		return -ENOENT;
	}
	if (rd.open && rd.id == id) {
		reader_close_locked();
	}
	seg_path(path, id, e.type, false);
	err = fs_unlink(path);
	if (err == 0 || err == -ENOENT) {
		owl_seg_index_remove(&ix, id);
		/* Whole blocks come back; metadata is noise. statvfs corrects us later. */
		space.freed_since_stat += ROUND_UP((uint64_t)e.size, BLOCK);
		err = 0;
	}
	maybe_refresh_space_locked();
	check_full_locked();
	k_mutex_unlock(&lock);

	LOG_INF("segment %u deleted (%d)", (unsigned int)id, err);
	if (space_cb) {
		space_cb();
	}
	return err;
}

void storage_get_usage(struct storage_usage *u)
{
	k_spinlock_key_t key = k_spin_lock(&pub_lock);

	*u = pub;
	k_spin_unlock(&pub_lock, key);
}

void storage_set_space_cb(storage_space_cb_t cb)
{
	space_cb = cb;
}

#if defined(CONFIG_ZTEST)
void storage_test_power_cut(void)
{
	/* As if the battery fell out: nothing is closed or flushed, RAM is lost. */
	fs_unmount(&FS_FSTAB_ENTRY(DT_NODELABEL(owl_lfs)));
	memset(&aw, 0, sizeof(aw));
	memset(&ew, 0, sizeof(ew));
	memset(&rd, 0, sizeof(rd));
	memset(&space, 0, sizeof(space));
	memset(&st, 0, sizeof(st));
}
#endif

void storage_flush(void)
{
	storage_seg_end();
	k_mutex_lock(&lock, K_FOREVER);
	events_close_locked();
	reader_close_locked();
	k_mutex_unlock(&lock);
}
