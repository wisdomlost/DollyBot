#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PROTOCOL_MAX_BYTES 256
typedef enum { CMD_DRIVE, CMD_STOP, CMD_PING } command_type_t;
typedef struct {
    command_type_t type;
    uint32_t seq;
    float linear;
    float angular;
} command_t;
bool protocol_parse(const char *data, size_t length, command_t *out);
