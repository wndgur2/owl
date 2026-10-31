/*
 * BLE peripheral for protocol v1. See ble.h.
 *
 * Threads:
 *   BT RX (Zephyr) : GATT write callback copies the CONTROL request into a queue
 *   ble_ctl        : parses it (control.c), does the work, notifies the response
 *   ble_tx         : streams DATA chunks for FETCH_SEGMENT
 * Doing the work outside the BT RX context keeps flash reads/CRC (up to ~1 s
 * for a big segment) from stalling the Bluetooth host.
 *
 * Flow control: each DATA notification takes a credit; the stack gives it back
 * (notify_done) once the packet has left. With a few credits in flight the link
 * stays busy without exhausting the host's TX buffers.
 *
 * Security (protocol §2.4): only the STATUS value is readable on a plain link.
 * CONTROL writes and every CCCD need an encrypted link, which only a bonded phone
 * can bring up (LE Secure Connections, Just Works: the pendant has no display or
 * keypad). The Bluetooth host checks those GATT permissions before our callbacks
 * run and answers ATT "Insufficient Authentication" (no bond) or "Insufficient
 * Encryption" (bonded, not encrypted yet). Who may pair at all is pair_policy.c;
 * bonds live in the settings partition in internal RRAM (prj.conf).
 */

#include <errno.h>
#include <stdio.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>

#include "app.h"
#include "audio.h"
#include "ble.h"
#include "control.h"
#include "pair_policy.h"
#include "storage.h"
#include "workq.h"

LOG_MODULE_REGISTER(ble, CONFIG_OWL_LOG_LEVEL);

#define OWL_UUID(x) BT_UUID_128_ENCODE(0xa2690000 | (x), 0x8c8b, 0x40f7, 0xa91b, 0x3ffa7ab560b9)

static const struct bt_uuid_128 svc_uuid = BT_UUID_INIT_128(OWL_UUID(OWL_UUID_SERVICE));
static const struct bt_uuid_128 status_uuid = BT_UUID_INIT_128(OWL_UUID(OWL_UUID_STATUS));
static const struct bt_uuid_128 control_uuid = BT_UUID_INIT_128(OWL_UUID(OWL_UUID_CONTROL));
static const struct bt_uuid_128 data_uuid = BT_UUID_INIT_128(OWL_UUID(OWL_UUID_DATA));

/* ~1 s advertising interval (units of 0.625 ms), decisions #15. */
#define ADV_INT_MIN 1600 /* 1000 ms */
#define ADV_INT_MAX 1680 /* 1050 ms */

#define TX_CREDITS 6

static struct bt_conn *conn;
static K_MUTEX_DEFINE(conn_lock);
static atomic_t status_subscribed;
static atomic_t control_subscribed;
static atomic_t data_subscribed;

static K_SEM_DEFINE(tx_credits, TX_CREDITS, TX_CREDITS);

/* ------------------------------------------------------------ GATT table */

static ssize_t read_status(struct bt_conn *c, const struct bt_gatt_attr *attr, void *buf,
			   uint16_t len, uint16_t offset)
{
	struct owl_status s;
	uint8_t v[OWL_STATUS_LEN];

	app_fill_status(&s);
	owl_status_pack(&s, v);
	return bt_gatt_attr_read(c, attr, buf, len, offset, v, sizeof(v));
}

static ssize_t write_control(struct bt_conn *c, const struct bt_gatt_attr *attr,
			     const void *buf, uint16_t len, uint16_t offset, uint8_t flags);

static void status_ccc(const struct bt_gatt_attr *attr, uint16_t value)
{
	atomic_set(&status_subscribed, value == BT_GATT_CCC_NOTIFY);
}

static void control_ccc(const struct bt_gatt_attr *attr, uint16_t value)
{
	atomic_set(&control_subscribed, value == BT_GATT_CCC_NOTIFY);
}

static void data_ccc(const struct bt_gatt_attr *attr, uint16_t value)
{
	atomic_set(&data_subscribed, value == BT_GATT_CCC_NOTIFY);
}

BT_GATT_SERVICE_DEFINE(owl_svc,
	BT_GATT_PRIMARY_SERVICE(&svc_uuid),
	BT_GATT_CHARACTERISTIC(&status_uuid.uuid, BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ, read_status, NULL, NULL),
	/* STATUS value stays readable unencrypted (no recordings in it, and it tells
	 * an unpaired phone whether pairing is open: flag bit4). Subscribing does not.
	 */
	BT_GATT_CCC(status_ccc, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE_ENCRYPT),
	BT_GATT_CHARACTERISTIC(&control_uuid.uuid, BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_WRITE_ENCRYPT, NULL, write_control, NULL),
	BT_GATT_CCC(control_ccc, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE_ENCRYPT),
	BT_GATT_CHARACTERISTIC(&data_uuid.uuid, BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_NONE, NULL,
			       NULL, NULL),
	BT_GATT_CCC(data_ccc, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE_ENCRYPT),
);

/* Attribute indices inside owl_svc.attrs (service, chrc decl, value, ccc, ...). */
#define ATTR_STATUS  (&owl_svc.attrs[2])
#define ATTR_CONTROL (&owl_svc.attrs[5])
#define ATTR_DATA    (&owl_svc.attrs[8])

static struct bt_conn *conn_get(void)
{
	struct bt_conn *c = NULL;

	k_mutex_lock(&conn_lock, K_FOREVER);
	if (conn) {
		c = bt_conn_ref(conn);
	}
	k_mutex_unlock(&conn_lock);
	return c;
}

/* ATT payload per notification for the negotiated MTU. */
static uint16_t att_payload(struct bt_conn *c)
{
	uint16_t mtu = bt_gatt_get_mtu(c);

	return mtu > 3 ? mtu - 3 : 20;
}

/* --------------------------------------------------------- DATA stream */

static struct {
	struct k_spinlock lock;
	uint32_t id;
	uint32_t offset;
	uint32_t gen; /* bumped by every FETCH / ABORT; the streamer stops on change */
	bool pending;
} fetch_req;

static K_SEM_DEFINE(tx_kick, 0, 1);
static atomic_t tx_active;
static atomic_t tx_id;

/* CRC of what was streamed, if it was streamed in order from offset 0. */
static struct {
	struct k_spinlock lock;
	uint32_t id;
	uint32_t pos;
	uint32_t crc;
	bool valid;
} crc_cache;

static void notify_done(struct bt_conn *c, void *user_data)
{
	k_sem_give(&tx_credits);
}

static uint32_t fetch_gen(void)
{
	k_spinlock_key_t key = k_spin_lock(&fetch_req.lock);
	uint32_t g = fetch_req.gen;

	k_spin_unlock(&fetch_req.lock, key);
	return g;
}

static void crc_track(uint32_t id, uint32_t off, const uint8_t *data, uint32_t n)
{
	k_spinlock_key_t key = k_spin_lock(&crc_cache.lock);

	if (off == 0) {
		crc_cache.id = id;
		crc_cache.pos = 0;
		crc_cache.crc = 0;
		crc_cache.valid = true;
	}
	if (crc_cache.valid && crc_cache.id == id && crc_cache.pos == off) {
		crc_cache.crc = owl_crc32(crc_cache.crc, data, n);
		crc_cache.pos += n;
	}
	k_spin_unlock(&crc_cache.lock, key);
}

static void tx_thread(void *a, void *b, void *c)
{
	static uint8_t pkt[OWL_ATT_PAYLOAD];

	for (;;) {
		k_sem_take(&tx_kick, K_FOREVER);

		k_spinlock_key_t key = k_spin_lock(&fetch_req.lock);
		bool go = fetch_req.pending;
		uint32_t id = fetch_req.id;
		uint32_t off = fetch_req.offset;
		uint32_t gen = fetch_req.gen;

		fetch_req.pending = false;
		k_spin_unlock(&fetch_req.lock, key);
		if (!go) {
			continue;
		}

		struct bt_conn *cn = conn_get();

		if (!cn) {
			continue;
		}
		atomic_set(&tx_id, id);
		atomic_set(&tx_active, 1);

		uint16_t chunk = MIN(OWL_DATA_CHUNK_MAX, att_payload(cn) - OWL_DATA_HDR_LEN);
		uint32_t start = off;
		int64_t t0 = k_uptime_get();

		while (fetch_gen() == gen) {
			int n = storage_read(id, off, &pkt[OWL_DATA_HDR_LEN], chunk);

			if (n <= 0) {
				if (n < 0) {
					LOG_WRN("fetch %u read at %u: %d", id, off, n);
				}
				break;
			}
			if (k_sem_take(&tx_credits, K_SECONDS(5))) {
				LOG_WRN("fetch %u: link stalled, giving up", id);
				break;
			}
			owl_put_u32(&pkt[0], id);
			owl_put_u32(&pkt[4], off);

			struct bt_gatt_notify_params np = {
				.attr = ATTR_DATA,
				.data = pkt,
				.len = OWL_DATA_HDR_LEN + n,
				.func = notify_done,
			};
			int err = bt_gatt_notify_cb(cn, &np);

			if (err == -ENOMEM) {
				k_sem_give(&tx_credits);
				k_msleep(5);
				continue; /* same offset again */
			}
			if (err) {
				k_sem_give(&tx_credits);
				LOG_WRN("fetch %u notify: %d", id, err);
				break;
			}
			crc_track(id, off, &pkt[OWL_DATA_HDR_LEN], n);
			off += n;
		}

		int64_t ms = k_uptime_get() - t0;

		LOG_INF("fetch %u: sent %u..%u in %d ms (%u B/s)", id, start, off, (int)ms,
			ms > 0 ? (unsigned int)((off - start) * 1000LL / ms) : 0);
		atomic_set(&tx_active, 0);
		bt_conn_unref(cn);
	}
}

K_THREAD_DEFINE(ble_tx_tid, 3072, tx_thread, NULL, NULL, NULL, 7, 0, 0);

/* Stops a running stream and waits (briefly) until it has let go of the file. */
static void stream_stop(void)
{
	k_spinlock_key_t key = k_spin_lock(&fetch_req.lock);

	fetch_req.gen++;
	fetch_req.pending = false;
	k_spin_unlock(&fetch_req.lock, key);
	for (int i = 0; i < 100 && atomic_get(&tx_active); i++) {
		k_msleep(10);
	}
}

/* ------------------------------------------------------- security (§2.4) */

static struct owl_pair_policy pair_policy;
static struct k_spinlock pair_lock;

static void count_bond(const struct bt_bond_info *info, void *user_data)
{
	(*(int *)user_data)++;
}

static int bond_count(void)
{
	int n = 0;

	bt_foreach_bond(BT_ID_DEFAULT, count_bond, &n);
	return n;
}

bool ble_pairing_open(void)
{
	int bonds = bond_count();
	k_spinlock_key_t key = k_spin_lock(&pair_lock);
	bool open = owl_pair_allowed(&pair_policy, k_uptime_get(), bonds);

	k_spin_unlock(&pair_lock, key);
	return open;
}

/* SMP asks this on every Pairing Request (BT RX thread). Re-encrypting with an
 * existing bond is not pairing and never gets here.
 */
static enum bt_security_err pairing_accept(struct bt_conn *c,
					   const struct bt_conn_pairing_feat *feat)
{
	if (!ble_pairing_open()) {
		LOG_WRN("pairing refused: a phone is bonded and the window is closed");
		return BT_SECURITY_ERR_PAIR_NOT_ALLOWED;
	}
	LOG_INF("pairing accepted");
	return BT_SECURITY_ERR_SUCCESS;
}

/* No display / keypad callbacks: SMP reports NoInputNoOutput -> Just Works. */
static const struct bt_conn_auth_cb auth_cb = {
	.pairing_accept = pairing_accept,
};

/* One bond at a time: the newest phone stays, older bonds go (§2.4). Not done
 * in pairing_complete itself, which runs on the BT RX thread: deleting keys
 * writes the settings partition.
 */
static bt_addr_le_t bond_keep;

static void collect_stale(const struct bt_bond_info *info, void *user_data)
{
	bt_addr_le_t *stale = user_data;

	if (!bt_addr_le_eq(&info->addr, &bond_keep) && bt_addr_le_eq(stale, BT_ADDR_LE_ANY)) {
		bt_addr_le_copy(stale, &info->addr);
	}
}

static void prune_bonds_fn(struct k_work *w)
{
	for (int i = 0; i < CONFIG_BT_MAX_PAIRED; i++) {
		bt_addr_le_t stale;

		bt_addr_le_copy(&stale, BT_ADDR_LE_ANY);
		bt_foreach_bond(BT_ID_DEFAULT, collect_stale, &stale);
		if (bt_addr_le_eq(&stale, BT_ADDR_LE_ANY)) {
			break;
		}
		LOG_INF("removing the previous phone's bond");
		bt_unpair(BT_ID_DEFAULT, &stale);
	}
	ble_notify_status();
}

static K_WORK_DEFINE(prune_bonds_work, prune_bonds_fn);

static void pairing_complete(struct bt_conn *c, bool bonded)
{
	k_spinlock_key_t key = k_spin_lock(&pair_lock);

	owl_pair_on_bonded(&pair_policy);
	k_spin_unlock(&pair_lock, key);
	/* CONFIG_BT_BONDING_REQUIRED: a pairing that would not bond is refused. */
	LOG_INF("paired, bonded %d", bonded);
	bt_addr_le_copy(&bond_keep, bt_conn_get_dst(c));
	k_work_submit_to_queue(&app_workq, &prune_bonds_work);
}

static void pairing_failed(struct bt_conn *c, enum bt_security_err reason)
{
	LOG_WRN("pairing failed: %d", reason);
}

static void bond_deleted(uint8_t id, const bt_addr_le_t *peer)
{
	LOG_INF("bond deleted");
}

static struct bt_conn_auth_info_cb auth_info_cb = {
	.pairing_complete = pairing_complete,
	.pairing_failed = pairing_failed,
	.bond_deleted = bond_deleted,
};

/* A link that never got encrypted is a stranger or a refused phone: let it go so
 * the device's only connection is free for the owner.
 */
static void enc_timeout_fn(struct k_work *w)
{
	struct bt_conn *c = conn_get();

	if (!c) {
		return;
	}
	if (bt_conn_get_security(c) < BT_SECURITY_L2) {
		LOG_WRN("not encrypted after %d s, disconnecting", OWL_UNENCRYPTED_TIMEOUT_MS / 1000);
		bt_conn_disconnect(c, BT_HCI_ERR_AUTH_FAIL);
	}
	bt_conn_unref(c);
}

static K_WORK_DELAYABLE_DEFINE(enc_timeout_work, enc_timeout_fn);

static void unpair_fn(struct k_work *w)
{
	/* All bonds; also drops the link of the phone that asked. */
	int err = bt_unpair(BT_ID_DEFAULT, BT_ADDR_LE_ANY);

	LOG_INF("UNPAIR: bonds deleted (%d), pairing open", err);
	ble_notify_status();
}

/* Delayed so the UNPAIR response leaves before the link goes down. */
static K_WORK_DELAYABLE_DEFINE(unpair_work, unpair_fn);

/* ------------------------------------------------------- CONTROL ops */

static bool fetch_kick_pending;

static void op_time_sync(uint64_t unix_ms, int16_t tz, uint32_t *boot_count, uint64_t *uptime)
{
	*boot_count = storage_boot_count();
	*uptime = (uint64_t)k_uptime_get();
	/* The device keeps no wall clock (§4); this log is only for bring-up. */
	LOG_INF("time sync: phone %llu ms, tz %d -> boot %u uptime %llu", unix_ms, tz,
		*boot_count, *uptime);
}

static int op_list(uint32_t after_id, struct owl_list_entry *out, int max)
{
	/* Also seal here (not only on connect), so events between connect and LIST
	 * are in this sync too. A listed segment never changes size: only the open,
	 * unlisted event log grows.
	 */
	storage_events_rotate();
	return storage_list(after_id, out, max);
}

static int op_fetch(uint32_t id, uint32_t offset)
{
	struct owl_list_entry e;

	if (!storage_seg_info(id, &e)) {
		return OWL_RESULT_NO_SEGMENT;
	}
	if (offset > e.size) {
		return OWL_RESULT_BAD_LENGTH;
	}
	if (!atomic_get(&data_subscribed)) {
		LOG_WRN("FETCH without DATA subscription");
	}
	k_spinlock_key_t key = k_spin_lock(&fetch_req.lock);

	fetch_req.gen++; /* stops any stream in progress */
	fetch_req.id = id;
	fetch_req.offset = offset;
	fetch_req.pending = true;
	k_spin_unlock(&fetch_req.lock, key);
	/* The stream starts after the OK response is queued (see ctl_thread). */
	fetch_kick_pending = true;
	return OWL_RESULT_OK;
}

static int op_ack(uint32_t id, uint32_t crc)
{
	struct owl_list_entry e;
	uint32_t mine = 0;
	bool cached = false;

	if (!storage_seg_info(id, &e)) {
		return OWL_RESULT_NO_SEGMENT;
	}
	if (atomic_get(&tx_active) && (uint32_t)atomic_get(&tx_id) == id) {
		/* Stop that stream, but keep a FETCH for another segment that may be queued. */
		k_spinlock_key_t k = k_spin_lock(&fetch_req.lock);

		fetch_req.gen++;
		k_spin_unlock(&fetch_req.lock, k);
		for (int i = 0; i < 100 && atomic_get(&tx_active) &&
				(uint32_t)atomic_get(&tx_id) == id;
		     i++) {
			k_msleep(10);
		}
	}

	k_spinlock_key_t key = k_spin_lock(&crc_cache.lock);

	if (crc_cache.valid && crc_cache.id == id && crc_cache.pos == e.size) {
		mine = crc_cache.crc;
		cached = true;
	}
	k_spin_unlock(&crc_cache.lock, key);

	if (!cached && storage_crc(id, &mine)) {
		return OWL_RESULT_BUSY;
	}
	if (mine != crc) {
		LOG_WRN("ack %u: crc %08x != ours %08x, keeping it", id, crc, mine);
		return OWL_RESULT_CRC_MISMATCH;
	}
	/* Only here, after a verified ACK, is a segment ever deleted (PRD D4). */
	if (storage_delete(id)) {
		return OWL_RESULT_BUSY;
	}
	return OWL_RESULT_OK;
}

static void op_abort(void)
{
	stream_stop();
}

static int op_set_recording(bool on)
{
	return app_set_recording(on, OWL_CAUSE_APP);
}

static int op_set_config(uint8_t key, uint32_t value)
{
	return app_set_config(key, value);
}

static int op_unpair(void)
{
	stream_stop();
	k_work_schedule_for_queue(&app_workq, &unpair_work, K_MSEC(300));
	return OWL_RESULT_OK;
}

static const struct owl_ctl_ops ctl_ops = {
	.time_sync = op_time_sync,
	.list = op_list,
	.fetch = op_fetch,
	.ack = op_ack,
	.abort = op_abort,
	.set_recording = op_set_recording,
	.set_config = op_set_config,
	.unpair = op_unpair,
};

struct ctl_msg {
	uint8_t len;
	uint8_t data[32];
};

K_MSGQ_DEFINE(ctl_q, sizeof(struct ctl_msg), 8, 4);

static ssize_t write_control(struct bt_conn *c, const struct bt_gatt_attr *attr,
			     const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	struct ctl_msg m;

	if (offset != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}
	m.len = (uint8_t)MIN(len, sizeof(m.data));
	memcpy(m.data, buf, m.len);
	if (m.len >= 1 && m.data[0] == OWL_OP_ABORT) {
		/* Stop the stream right away, not after queued requests. */
		k_spinlock_key_t key = k_spin_lock(&fetch_req.lock);

		fetch_req.gen++;
		fetch_req.pending = false;
		k_spin_unlock(&fetch_req.lock, key);
	}
	if (k_msgq_put(&ctl_q, &m, K_NO_WAIT)) {
		/* Phone sent too many requests without waiting for answers. */
		uint8_t busy[3] = {m.data[0] | OWL_RESPONSE_BIT, m.len > 1 ? m.data[1] : 0,
				   OWL_RESULT_BUSY};

		bt_gatt_notify(c, ATTR_CONTROL, busy, sizeof(busy));
	}
	return len;
}

static void ctl_thread(void *a, void *b, void *c)
{
	static uint8_t resp[OWL_ATT_PAYLOAD];
	struct ctl_msg m;

	for (;;) {
		k_msgq_get(&ctl_q, &m, K_FOREVER);

		struct bt_conn *cn = conn_get();

		if (!cn) {
			continue;
		}
		fetch_kick_pending = false;
		size_t n = owl_control_handle(&ctl_ops, m.data, m.len, resp,
					      MIN(sizeof(resp), att_payload(cn)));

		LOG_DBG("ctl op %02x seq %u -> result %u", m.data[0], m.len > 1 ? m.data[1] : 0,
			n >= 3 ? resp[2] : 0xff);
		if (n) {
			int err = bt_gatt_notify(cn, ATTR_CONTROL, resp, n);

			if (err) {
				LOG_WRN("ctl notify: %d", err);
			}
		}
		if (fetch_kick_pending) {
			k_sem_give(&tx_kick);
		}
		bt_conn_unref(cn);
		ble_notify_status(); /* pending count / flags may have changed */
	}
}

K_THREAD_DEFINE(ble_ctl_tid, 4096, ctl_thread, NULL, NULL, NULL, 8, 0, 0);

/* ------------------------------------------------------- STATUS notify */

static void status_work_fn(struct k_work *w)
{
	struct bt_conn *cn = conn_get();

	if (!cn) {
		return;
	}
	if (atomic_get(&status_subscribed)) {
		struct owl_status s;
		uint8_t v[OWL_STATUS_LEN];

		app_fill_status(&s);
		owl_status_pack(&s, v);
		bt_gatt_notify(cn, ATTR_STATUS, v, sizeof(v));
	}
	bt_conn_unref(cn);
}

static K_WORK_DELAYABLE_DEFINE(status_work, status_work_fn);

void ble_notify_status(void)
{
	/* Coalesce bursts of changes into one notification. Not on the system work
	 * queue: building STATUS takes the storage lock, which may wait on flash.
	 */
	k_work_schedule_for_queue(&app_workq, &status_work, K_MSEC(100));
}

/* ------------------------------------------------------- advertising */

static char name[sizeof("Owl-XXXX")];

static int adv_start(void)
{
	static const uint8_t svc_uuid_bytes[] = {OWL_UUID(OWL_UUID_SERVICE)};
	const struct bt_data ad[] = {
		BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
		BT_DATA(BT_DATA_UUID128_ALL, svc_uuid_bytes, sizeof(svc_uuid_bytes)),
		BT_DATA(BT_DATA_NAME_COMPLETE, name, strlen(name)),
	}; /* 3 + 18 + 10 = 31 bytes: exactly one legacy advertising packet */
	const struct bt_le_adv_param param =
		BT_LE_ADV_PARAM_INIT(BT_LE_ADV_OPT_CONN, ADV_INT_MIN, ADV_INT_MAX, NULL);

	int err = bt_le_adv_start(&param, ad, ARRAY_SIZE(ad), NULL, 0);

	if (err && err != -EALREADY) {
		LOG_ERR("adv start: %d", err);
		return err;
	}
	LOG_INF("advertising as %s", name);
	return 0;
}

static atomic_t stopped;

static void adv_work_fn(struct k_work *w)
{
	if (!atomic_get(&stopped)) {
		adv_start();
	}
}

static K_WORK_DEFINE(adv_work, adv_work_fn);

/* ------------------------------------------------------- connection */

static void mtu_exchanged(struct bt_conn *c, uint8_t err, struct bt_gatt_exchange_params *p)
{
	if (err) {
		LOG_WRN("mtu exchange: %u", err);
	}
}

static struct bt_gatt_exchange_params mtu_params = {.func = mtu_exchanged};

static void seal_events_fn(struct k_work *w)
{
	storage_events_rotate();
}

static K_WORK_DEFINE(seal_events_work, seal_events_fn);

static void connected(struct bt_conn *c, uint8_t err)
{
	if (err) {
		LOG_WRN("connect failed: 0x%02x", err);
		return;
	}
	k_mutex_lock(&conn_lock, K_FOREVER);
	if (conn) {
		k_mutex_unlock(&conn_lock);
		bt_conn_disconnect(c, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
		return; /* one phone at a time */
	}
	conn = bt_conn_ref(c);
	k_mutex_unlock(&conn_lock);
	k_sem_reset(&tx_credits);
	for (int i = 0; i < TX_CREDITS; i++) {
		k_sem_give(&tx_credits);
	}
	LOG_INF("connected");

	/* A bonded phone: send a Security Request so it encrypts right away with
	 * its stored key, instead of waiting for an ATT error. Unknown centrals are
	 * not asked: that would pop a pairing dialog on any phone that connects.
	 */
	if (bt_le_bond_exists(BT_ID_DEFAULT, bt_conn_get_dst(c))) {
		int e = bt_conn_set_security(c, BT_SECURITY_L2);

		if (e) {
			LOG_WRN("security request: %d", e);
		}
	}
	k_work_schedule(&enc_timeout_work, K_MSEC(OWL_UNENCRYPTED_TIMEOUT_MS));

	/* Seal the open event log into a listable segment for this sync (flash work:
	 * not in this BT RX callback). Later events start a new event segment.
	 */
	k_work_submit_to_queue(&app_workq, &seal_events_work);

	/* Ask for the fast-transfer link: MTU 247, 2M PHY, 251-byte packets (§2).
	 * Phones usually start the MTU exchange themselves; doing it from our side
	 * too covers centrals that don't (else DATA chunks would be 12 bytes).
	 */
	int e = bt_gatt_exchange_mtu(c, &mtu_params);

	if (e && e != -EALREADY) {
		LOG_WRN("mtu exchange: %d", e);
	}
	e = bt_conn_le_phy_update(c, BT_CONN_LE_PHY_PARAM_2M);

	if (e) {
		LOG_WRN("phy update: %d", e);
	}
	e = bt_conn_le_data_len_update(c, BT_LE_DATA_LEN_PARAM_MAX);
	if (e) {
		LOG_WRN("data len update: %d", e);
	}
}

static void disconnected(struct bt_conn *c, uint8_t reason)
{
	k_mutex_lock(&conn_lock, K_FOREVER);
	if (conn != c) {
		k_mutex_unlock(&conn_lock);
		return;
	}
	bt_conn_unref(conn);
	conn = NULL;
	k_mutex_unlock(&conn_lock);
	k_work_cancel_delayable(&enc_timeout_work);

	k_spinlock_key_t key = k_spin_lock(&fetch_req.lock);

	fetch_req.gen++;
	fetch_req.pending = false;
	k_spin_unlock(&fetch_req.lock, key);
	LOG_INF("disconnected (0x%02x)", reason);
}

static void recycled(void)
{
	/* The connection object is free again: advertise so the phone can come back. */
	k_work_submit(&adv_work);
}

static void le_param_updated(struct bt_conn *c, uint16_t interval, uint16_t latency,
			     uint16_t timeout)
{
	LOG_INF("conn params: interval %u.%02u ms, latency %u", interval * 125 / 100,
		interval * 125 % 100, latency);
}

static void le_phy_updated(struct bt_conn *c, struct bt_conn_le_phy_info *p)
{
	LOG_INF("phy: tx %u rx %u", p->tx_phy, p->rx_phy);
}

static void le_data_len_updated(struct bt_conn *c, struct bt_conn_le_data_len_info *i)
{
	LOG_INF("data len: tx %u rx %u", i->tx_max_len, i->rx_max_len);
}

static void security_changed(struct bt_conn *c, bt_security_t level, enum bt_security_err err)
{
	if (err) {
		/* e.g. the phone lost its key for us, or we refused to pair */
		LOG_WRN("security failed: level %d err %d", level, err);
		return;
	}
	LOG_INF("security level %d", level);
}

BT_CONN_CB_DEFINE(conn_cbs) = {
	.connected = connected,
	.disconnected = disconnected,
	.recycled = recycled,
	.le_param_updated = le_param_updated,
	.le_phy_updated = le_phy_updated,
	.le_data_len_updated = le_data_len_updated,
	.security_changed = security_changed,
};

static void mtu_updated(struct bt_conn *c, uint16_t tx, uint16_t rx)
{
	LOG_INF("mtu: tx %u rx %u", tx, rx);
}

static struct bt_gatt_cb gatt_cbs = {
	.att_mtu_updated = mtu_updated,
};

/* ---------------------------------------------------------------- public */

int ble_init(void)
{
	bt_addr_le_t addrs[1];
	size_t count = ARRAY_SIZE(addrs);
	int err;

	err = bt_enable(NULL);
	if (err) {
		LOG_ERR("bt_enable: %d", err);
		return err;
	}
	bt_gatt_cb_register(&gatt_cbs);

	err = bt_conn_auth_cb_register(&auth_cb);
	if (!err) {
		err = bt_conn_auth_info_cb_register(&auth_info_cb);
	}
	if (err) {
		LOG_ERR("auth callbacks: %d", err);
		return err;
	}
	/* Bonds and our identity address (BT_SETTINGS). Must come after bt_enable
	 * and before bt_id_get: the identity is only final once settings are loaded.
	 */
	err = settings_load();
	if (err) {
		LOG_ERR("settings load: %d", err);
	}
	owl_pair_policy_init(&pair_policy, k_uptime_get()); /* window starts at power-on */
	LOG_INF("%d bonded phone(s), pairing %s", bond_count(),
		ble_pairing_open() ? "open" : "closed");

	/* Owl-XXXX: low 2 bytes of the (static random) address, as printed (§2). */
	bt_id_get(addrs, &count);
	snprintf(name, sizeof(name), "Owl-%02X%02X", count ? addrs[0].a.val[1] : 0,
		 count ? addrs[0].a.val[0] : 0);
	bt_set_name(name);

	return adv_start();
}

bool ble_is_connected(void)
{
	struct bt_conn *c = conn_get();

	if (c) {
		bt_conn_unref(c);
	}
	return c != NULL;
}

void ble_stop(void)
{
	atomic_set(&stopped, 1);
	stream_stop();
	bt_le_adv_stop();

	struct bt_conn *c = conn_get();

	if (c) {
		bt_conn_disconnect(c, BT_HCI_ERR_REMOTE_POWER_OFF);
		bt_conn_unref(c);
		for (int i = 0; i < 50 && ble_is_connected(); i++) {
			k_msleep(10);
		}
	}
}
