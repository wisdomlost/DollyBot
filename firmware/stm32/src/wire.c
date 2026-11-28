#include "wire.h"
#include <string.h>
uint16_t wire_crc(const uint8_t *p, size_t n) {
    uint16_t crc = 0xffff;
    while (n--) {
        crc ^= (uint16_t)*p++ << 8;
        for (unsigned i = 0; i < 8; ++i)
            crc = (uint16_t)((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0));
    }
    return crc;
}
size_t wire_encode(uint8_t *o, uint8_t type, uint16_t seq, uint32_t session, const uint8_t *p,
                   uint16_t n) {
    if (n > 256)
        return 0;
    o[0] = 0xa5;
    o[1] = 0x5a;
    o[2] = 1;
    o[3] = type;
    put16(o + 4, seq);
    put16(o + 6, n);
    put32(o + 8, session);
    if (n)
        memcpy(o + 12, p, n);
    put16(o + 12 + n, wire_crc(o + 2, 10 + n));
    return 14 + n;
}
void wire_feed(Decoder *d, uint8_t byte, Receiver receive) {
    d->buf[d->used++] = byte;
    while (d->used) {
        unsigned drop = 1;
        if (d->buf[0] == 0xa5 && (d->used < 2 || d->buf[1] == 0x5a)) {
            if (d->used < 12)
                return;
            unsigned n = u16(d->buf + 6);
            if (n <= 256) {
                if (d->used < 14 + n)
                    return;
                if (wire_crc(d->buf + 2, 10 + n) == u16(d->buf + 12 + n)) {
                    receive(d->buf);
                    drop = 14 + n;
                }
            }
        }
        d->used -= drop;
        memmove(d->buf, d->buf + drop, d->used);
    }
}
