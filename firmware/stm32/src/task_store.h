#pragma once
#include "motion.h"
enum { MAX_POINTS = 4096, POSE_BYTES = 22, MAX_NAME = 512, SLOT_BYTES = 128 * 1024 };
typedef struct {
    uint32_t magic, generation, count, name_bytes, repeats, crc, seal;
} SlotHeader;
/* Slot implementation maps the last two Flash sectors. Program only erased words. */
const uint8_t *slot_address(unsigned slot);
bool slot_erase(unsigned slot);
bool slot_program(unsigned slot, unsigned offset, const void *data, unsigned bytes);
void store_init(void);
void store_abort(void);
bool store_busy(void);
bool store_begin(unsigned kind, unsigned flags, uint32_t name_len, uint32_t count);
bool store_chunk(uint32_t offset, const uint8_t *p, unsigned n);
bool store_commit(void);
uint32_t store_count(void);
uint32_t store_repeats(void);
void store_set_repeats(uint8_t repeats);
Sample store_read(uint32_t i);
