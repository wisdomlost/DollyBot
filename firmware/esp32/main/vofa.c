#include "vofa.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

size_t vofa_format(char *line, size_t capacity, const uint8_t *payload, size_t length)
{
    if (!length || length != tuning_payload_size(payload[0])) {
        return 0;
    }
    unsigned count = tuning_channel_count(payload[0]);
    static const char prefix[] = "tune:";
    size_t used = sizeof(prefix) - 1;
    if (capacity <= used) {
        return 0;
    }
    memcpy(line, prefix, used);
    for (unsigned i = 0; i < count; ++i) {
        const uint8_t *p = payload + 1 + 4 * i;
        uint32_t bits = p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
        float value;
        memcpy(&value, &bits, sizeof(value));
        if (!isfinite(value)) {
            return 0;
        }
        int n = snprintf(line + used, capacity - used, "%.9g%c", (double)value,
                         i + 1 == count ? '\n' : ',');
        if (n < 0 || (size_t)n >= capacity - used) {
            return 0;
        }
        used += (size_t)n;
    }
    return used;
}

bool vofa_command_byte(vofa_command_t *command, uint8_t byte, uint8_t *mode)
{
    if (byte != '\n') {
        if (command->used < sizeof(command->line)) {
            command->line[command->used++] = (char)byte;
        } else {
            command->overflow = true;
        }
        return false;
    }

    unsigned length = command->used;
    bool overflow = command->overflow;
    command->used = 0;
    command->overflow = false;
    if (overflow) {
        return false;
    }
    if (length && command->line[length - 1] == '\r') {
        --length;
    }
    static const char *const names[] = {
        [TUNE_OFF] = "OFF", [TUNE_SPEED] = "SPEED", [TUNE_RATE] = "RATE",
        [TUNE_ANGLE] = "ANGLE", [TUNE_ODOM] = "ODOM",
    };
    for (uint8_t i = TUNE_OFF; i <= TUNE_ODOM; ++i) {
        if (length == strlen(names[i]) && memcmp(command->line, names[i], length) == 0) {
            *mode = i;
            return true;
        }
    }
    return false;
}
