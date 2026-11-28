#pragma once

#include <stdint.h>

/* Both frames use session zero. A selection is one byte. A sample starts
 * with the mode byte followed by little-endian IEEE-754 binary32 values. */
enum {
    DOLLY_TUNING_SELECT = 0x70,
    DOLLY_TUNING_TYPE = 0x93,
    TUNE_OFF = 0,
    TUNE_SPEED,
    TUNE_RATE,
    TUNE_ANGLE,
    TUNE_ODOM
};

enum {
    TUNE_TIME = 0,
    TUNE_FLAGS = 1,
    TUNE_DATA = 2,
    TUNE_MAX_CHANNELS = TUNE_DATA + 12,
    TUNE_MAX_PAYLOAD = 1 + TUNE_MAX_CHANNELS * 4,
    TUNE_PERIOD_MS = 20,
    TUNE_REFRESH_MS = 250,
    TUNE_SELECTION_MS = 1000
};

static inline unsigned tuning_channel_count(uint8_t mode) {
    switch (mode) {
    case TUNE_SPEED: return TUNE_DATA + 12;
    case TUNE_RATE: return TUNE_DATA + 3;
    case TUNE_ANGLE: return TUNE_DATA + 4;
    case TUNE_ODOM: return TUNE_DATA + 8;
    default: return 0;
    }
}

static inline unsigned tuning_payload_size(uint8_t mode) {
    unsigned count = tuning_channel_count(mode);
    return count ? 1 + count * 4 : 0;
}

enum {
    TUNE_DRIVE_AUTHORIZED = 1u << 0,
    TUNE_IMU_VALID = 1u << 1,
    TUNE_CALIBRATED = 1u << 2,
    TUNE_GEOMETRY_VALID = 1u << 3,
    TUNE_REPLAY_YAW_ACTIVE = 1u << 4,
    TUNE_VOLTAGE_OK = 1u << 5,
    TUNE_POWER_FAULT = 1u << 6
};
