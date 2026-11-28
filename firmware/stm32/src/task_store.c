#include "task_store.h"
#include "wire.h"
#include <string.h>
#include <math.h>
#include <stddef.h>
#define MAGIC 0x314c4244u
#define SEALED 0x91c735a2u
static uint8_t staging[MAX_POINTS * POSE_BYTES + MAX_NAME + 3];
static uint32_t received, needed, kind;
static SlotHeader pending, active;
static unsigned current;
static uint32_t checksum(const SlotHeader *h, const uint8_t *data) {
    uint32_t crc = ~0u;
    for (unsigned part = 0; part < 2; ++part) {
        const uint8_t *p = part ? data : (const uint8_t *)h;
        unsigned n = part ? h->count * POSE_BYTES + h->name_bytes : offsetof(SlotHeader, crc);
        while (n--) {
            crc ^= *p++;
            for (unsigned i = 0; i < 8; ++i)
                crc = (crc >> 1) ^ ((0u - (crc & 1u)) & 0xedb88320u);
        }
    }
    return ~crc;
}
static bool valid(const SlotHeader *h, const uint8_t *data) {
    return h->repeats >= 1 && h->repeats <= 20 && h->magic == MAGIC && h->seal == SEALED &&
           h->count && h->count <= MAX_POINTS && h->name_bytes && h->name_bytes <= MAX_NAME &&
           h->crc == checksum(h, data);
}
void store_init(void) {
    memset(&active, 0, sizeof active);
    current = 0;
    store_abort();
    for (unsigned i = 0; i < 2; ++i) {
        SlotHeader h;
        const uint8_t *p = slot_address(i);
        memcpy(&h, p, sizeof h);
        if (valid(&h, p + sizeof h) &&
            (!active.count || (int32_t)(h.generation - active.generation) > 0)) {
            current = i;
            active = h;
        }
    }
}
bool store_busy(void) { return needed != 0; }
void store_abort(void) { needed = received = 0; }
bool store_begin(unsigned k, unsigned flags, uint32_t name_len, uint32_t count) {
    if ((k != 1 && k != 2) || flags || !name_len || name_len > MAX_NAME || !count ||
        count > MAX_POINTS)
        return false;
    kind = k;
    pending = (SlotHeader){MAGIC, active.generation + 1, count, name_len, 1, 0, SEALED};
    received = 0;
    needed = name_len + count * POSE_BYTES;
    return true;
}
bool store_chunk(uint32_t off, const uint8_t *p, unsigned n) {
    if (!n || off != received || n > needed - received)
        return false;
    memcpy(staging + off, p, n);
    received += n;
    return true;
}
static Sample unpack(const uint8_t *p) {
    Sample s = {.time = u32(p), .frame = u32(p + 4)};
    s.pose =
        (Pose){(float)(int32_t)u32(p + 8), (float)(int32_t)u32(p + 12), (int16_t)u16(p + 16) * .01f,
               (int16_t)u16(p + 18) * .01f, (int16_t)u16(p + 20) * .01f};
    return s;
}
bool store_commit(void) {
    if (!needed || received != needed)
        return false;
    /* Validation may normalize earlier timestamps before rejecting a later sample.
     * Only a new BEGIN may replace that rejected staging buffer. */
    uint32_t length = needed;
    store_abort();
    uint8_t *p = staging + pending.name_bytes;
    Sample last = unpack(p);
    uint32_t timeline = 0;
    for (unsigned i = 0; i < pending.count; ++i, p += POSE_BYTES) {
        Sample s = unpack(p);
        if (!s.frame || s.frame != last.frame || fabsf(s.pose.x) > 100000 ||
            fabsf(s.pose.y) > 100000 || fabsf(s.pose.yaw) > 180 || fabsf(s.pose.pan) > 180 ||
            fabsf(s.pose.tilt) > 180)
            return false;
        if (i) {
            uint32_t dt = s.time - last.time;
            if (kind == 2) {
                float move = hypotf(s.pose.x - last.pose.x, s.pose.y - last.pose.y) / 200;
                float turn = fabsf(angle_delta(s.pose.yaw, last.pose.yaw)) / 90;
                dt = (uint32_t)ceilf(1000 * fmaxf(.2f, fmaxf(move, turn)));
            }
            if (!dt || dt > 600000 || timeline > 3600000 - dt)
                return false;
            timeline += dt;
        }
        put32(p, timeline);
        last = s;
    }
    unsigned next = 1 - current;
    pending.crc = checksum(&pending, staging);
    /* The seal is the last programmed word. Previous sector stays valid. */
    unsigned aligned = (length + 3u) & ~3u;
    memset(staging + length, 0xff, aligned - length);
    bool ok = slot_erase(next) && slot_program(next, 0, &pending, offsetof(SlotHeader, seal)) &&
              slot_program(next, sizeof pending, staging, aligned);
    if (ok)
        ok = pending.crc == checksum(&pending, slot_address(next) + sizeof pending) &&
             slot_program(next, offsetof(SlotHeader, seal), &pending.seal, 4);
    if (ok) {
        current = next;
        active = pending;
    }
    store_abort();
    return ok;
}
void store_set_repeats(uint8_t repeats) { pending.repeats = repeats; }
uint32_t store_repeats(void) { return active.count ? active.repeats : 1; }
uint32_t store_count(void) { return active.count; }
Sample store_read(uint32_t i) {
    if (i >= active.count)
        return (Sample){0};
    return unpack(slot_address(current) + sizeof active + active.name_bytes + i * POSE_BYTES);
}
