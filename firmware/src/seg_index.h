/*
 * In-RAM index of closed (listable) segments, sorted by id.
 *
 * Rebuilt from the file system at boot. LIST_SEGMENTS is answered from here so
 * paging through thousands of segments does not rescan the flash directory
 * every time. 8 bytes per entry; the caller provides the backing array.
 */

#ifndef OWL_SEG_INDEX_H_
#define OWL_SEG_INDEX_H_

#include <stdbool.h>
#include <stdint.h>

#include "owl_proto.h"

/* Segment size is at most a few MB, so it shares a word with the type. */
#define OWL_SEG_SIZE_MAX 0x00ffffffU

struct owl_seg_slot {
	uint32_t id;
	uint32_t size_type; /* size (24 bit) | type << 24 */
};

struct owl_seg_index {
	struct owl_seg_slot *slots;
	int cap;
	int count;
};

void owl_seg_index_init(struct owl_seg_index *ix, struct owl_seg_slot *slots, int cap);

/* Inserts (or updates) an entry. Returns 0, or -1 when full or size too big. */
int owl_seg_index_put(struct owl_seg_index *ix, uint32_t id, uint8_t type, uint32_t size);

/* Returns 0, or -1 if not present. */
int owl_seg_index_remove(struct owl_seg_index *ix, uint32_t id);

/* Returns true and fills *out if present. */
bool owl_seg_index_get(const struct owl_seg_index *ix, uint32_t id, struct owl_list_entry *out);

/* LIST_SEGMENTS paging: up to max entries with id > after_id, ascending. */
int owl_seg_index_list(const struct owl_seg_index *ix, uint32_t after_id,
		       struct owl_list_entry *out, int max);

/* Highest id in the index, or 0 if empty. */
uint32_t owl_seg_index_max_id(const struct owl_seg_index *ix);

#endif /* OWL_SEG_INDEX_H_ */
