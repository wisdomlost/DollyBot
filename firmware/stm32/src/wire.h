#pragma once
#include <stdint.h>
#include <stddef.h>
enum {
    HELLO = 1,
    PING = 2,
    MOTION = 0x10,
    GIMBAL = 0x11,
    STOP = 0x12,
    CALIBRATE = 0x20,
    TEACH_BEGIN = 0x30,
    TEACH_END = 0x31,
    TAKE_POINT = 0x32,
    UPLOAD_BEGIN = 0x40,
    UPLOAD_DATA = 0x41,
    COMMIT = 0x42,
    CAPABILITIES = 0x43,
    START = 0x44,
    LEASE = 0x45,
    ACK = 0x80,
    STATUS = 0x90,
    POINT = 0x91,
    POWER_STATUS = 0x92,
    MAX_FRAME = 270
};
enum {
    POWER_STATUS_SIZE = 10,
    POWER_MOTOR_TRIP = 1u << 0,
    POWER_GIMBAL_TRIP = 1u << 1,
    POWER_ADC_FAULT = 1u << 2,
    POWER_BATTERY_RANGE = 1u << 3
};
static inline uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static inline uint32_t u32(const uint8_t *p) { return u16(p) | (uint32_t)u16(p + 2) << 16; }
static inline void put16(uint8_t *p, uint16_t x) {
    p[0] = (uint8_t)x;
    p[1] = (uint8_t)(x >> 8);
}
static inline void put32(uint8_t *p, uint32_t x) {
    put16(p, (uint16_t)x);
    put16(p + 2, (uint16_t)(x >> 16));
}
uint16_t wire_crc(const uint8_t *p, size_t n);
size_t wire_encode(uint8_t *out, uint8_t type, uint16_t seq, uint32_t session, const uint8_t *p,
                   uint16_t n);
typedef void (*Receiver)(const uint8_t *frame);
typedef struct {
    uint8_t buf[MAX_FRAME];
    unsigned used;
} Decoder;
void wire_feed(Decoder *d, uint8_t byte, Receiver receive);
