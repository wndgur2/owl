/* Sorted segment index (backs LIST_SEGMENTS). */

#include <zephyr/ztest.h>

#include "seg_index.h"

static struct owl_seg_slot slots[8];
static struct owl_seg_index ix;

static void before(void *f)
{
	owl_seg_index_init(&ix, slots, ARRAY_SIZE(slots));
}

ZTEST(seg_index, test_out_of_order_insert_stays_sorted)
{
	const uint32_t ids[] = {5, 1, 9, 3, 7};
	struct owl_list_entry out[8];

	for (int i = 0; i < ARRAY_SIZE(ids); i++) {
		zassert_ok(owl_seg_index_put(&ix, ids[i], OWL_SEG_AUDIO, ids[i] * 100));
	}
	int n = owl_seg_index_list(&ix, 0, out, 8);

	zassert_equal(n, 5);
	for (int i = 0; i < n; i++) {
		zassert_equal(out[i].id, 1 + 2 * i);
		zassert_equal(out[i].size, out[i].id * 100);
	}
	zassert_equal(owl_seg_index_max_id(&ix), 9);
}

ZTEST(seg_index, test_list_after_and_max)
{
	struct owl_list_entry out[8];

	for (uint32_t id = 1; id <= 6; id++) {
		owl_seg_index_put(&ix, id, OWL_SEG_EVENTS, 42);
	}
	zassert_equal(owl_seg_index_list(&ix, 4, out, 8), 2);
	zassert_equal(out[0].id, 5);
	zassert_equal(out[0].type, OWL_SEG_EVENTS);
	zassert_equal(owl_seg_index_list(&ix, 0, out, 3), 3);
	zassert_equal(out[2].id, 3);
	zassert_equal(owl_seg_index_list(&ix, 6, out, 8), 0);
	zassert_equal(owl_seg_index_list(&ix, UINT32_MAX, out, 8), 0);
}

ZTEST(seg_index, test_remove_get_update)
{
	struct owl_list_entry e;

	owl_seg_index_put(&ix, 10, OWL_SEG_AUDIO, 1);
	owl_seg_index_put(&ix, 11, OWL_SEG_AUDIO, 2);
	zassert_ok(owl_seg_index_put(&ix, 10, OWL_SEG_AUDIO, 3), "update in place");
	zassert_equal(ix.count, 2);
	zassert_true(owl_seg_index_get(&ix, 10, &e));
	zassert_equal(e.size, 3);

	zassert_ok(owl_seg_index_remove(&ix, 10));
	zassert_false(owl_seg_index_get(&ix, 10, &e));
	zassert_equal(owl_seg_index_remove(&ix, 10), -1);
	zassert_equal(ix.count, 1);
	zassert_equal(owl_seg_index_max_id(&ix), 11);
}

ZTEST(seg_index, test_full_and_limits)
{
	for (uint32_t id = 1; id <= ARRAY_SIZE(slots); id++) {
		zassert_ok(owl_seg_index_put(&ix, id, OWL_SEG_AUDIO, id));
	}
	zassert_equal(owl_seg_index_put(&ix, 100, OWL_SEG_AUDIO, 1), -1, "full");
	owl_seg_index_remove(&ix, 1);
	zassert_equal(owl_seg_index_put(&ix, 100, OWL_SEG_AUDIO, OWL_SEG_SIZE_MAX + 1), -1,
		      "size must fit 24 bits");
	zassert_ok(owl_seg_index_put(&ix, 100, OWL_SEG_AUDIO, OWL_SEG_SIZE_MAX));
}

ZTEST_SUITE(seg_index, NULL, NULL, before, NULL, NULL);
