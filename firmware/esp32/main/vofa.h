#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "tuning_wire.h"

enum { VOFA_LINE_CAPACITY = TUNE_MAX_CHANNELS * 16 + 8 };
size_t vofa_format(char *line, size_t capacity, const uint8_t *payload, size_t length);

typedef struct {
    char line[6];
    unsigned used;
    bool overflow;
} vofa_command_t;

bool vofa_command_byte(vofa_command_t *command, uint8_t byte, uint8_t *mode);
