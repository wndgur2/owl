/* Sorted segment index. See seg_index.h. */

#include <string.h>

#include "seg_index.h"

void owl_seg_index_init(struct owl_seg_index *ix, struct owl_seg_slot *slots, int cap)
{
	ix->slots = slots;
	ix->cap = cap;
	ix->count = 0;
}

/* First position whose id is >= id (binary search). */
static int lower_bound(const struct owl_seg_index *ix, uint32_t id)
{
	int lo = 0, hi = ix->count;

	while (lo < hi) {
		int mid = lo + (hi - lo) / 2;

		if (ix->slots[mid].id < id) {
			lo = mid + 1;
		} else {
			hi = mid;
		}
	}
	return lo;
}

int owl_seg_index_put(struct owl_seg_index *ix, uint32_t id, uint8_t type, uint32_t size)
{
	if (size > OWL_SEG_SIZE_MAX) {
		return -1;
	}
	int pos = lower_bound(ix, id);
	uint32_t st = size | ((uint32_t)type << 24);

	if (pos < ix->count && ix->slots[pos].id == id) {
		ix->slots[pos].size_type = st;
		return 0;
	}
	if (ix->count >= ix->cap) {
		return -1;
	}
	/* New segments almost always have the highest id, so this memmove is usually empty. */
	memmove(&ix->slots[pos + 1], &ix->slots[pos],
		(size_t)(ix->count - pos) * sizeof(ix->slots[0]));
	ix->slots[pos].id = id;
	ix->slots[pos].size_type = st;
	ix->count++;
	return 0;
}

int owl_seg_index_remove(struct owl_seg_index *ix, uint32_t id)
{
	int pos = lower_bound(ix, id);

	if (pos >= ix->count || ix->slots[pos].id != id) {
		return -1;
	}
	memmove(&ix->slots[pos], &ix->slots[pos + 1],
		(size_t)(ix->count - pos - 1) * sizeof(ix->slots[0]));
	ix->count--;
	return 0;
}

static void to_entry(const struct owl_seg_slot *s, struct owl_list_entry *out)
{
	out->id = s->id;
	out->type = (uint8_t)(s->size_type >> 24);
	out->size = s->size_type & OWL_SEG_SIZE_MAX;
}

bool owl_seg_index_get(const struct owl_seg_index *ix, uint32_t id, struct owl_list_entry *out)
{
	int pos = lower_bound(ix, id);

	if (pos >= ix->count || ix->slots[pos].id != id) {
		return false;
	}
	if (out) {
		to_entry(&ix->slots[pos], out);
	}
	return true;
}

int owl_seg_index_list(const struct owl_seg_index *ix, uint32_t after_id,
		       struct owl_list_entry *out, int max)
{
	int n = 0;

	if (after_id == UINT32_MAX) {
		return 0;
	}
	for (int pos = lower_bound(ix, after_id + 1); pos < ix->count && n < max; pos++) {
		to_entry(&ix->slots[pos], &out[n++]);
	}
	return n;
}

uint32_t owl_seg_index_max_id(const struct owl_seg_index *ix)
{
	return ix->count ? ix->slots[ix->count - 1].id : 0;
}
