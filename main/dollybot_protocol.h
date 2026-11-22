#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    DOLLYBOT_MAX_FRAME = 270,
    DOLLYBOT_STATUS_FRAME = 48,
    DOLLYBOT_ACK_QUEUE_SIZE = 8,
};

typedef struct {
    uint32_t session;
    uint16_t seq;
    uint8_t request_type;
    uint8_t result;
} dollybot_ack_t;

typedef struct {
    uint8_t rx[DOLLYBOT_MAX_FRAME];
    size_t rx_length;
    uint32_t session;
    uint64_t started_ms;
    uint64_t next_status_ms;
    uint16_t status_seq;
    uint16_t last_motion_seq;
    dollybot_ack_t acks[DOLLYBOT_ACK_QUEUE_SIZE];
    size_t ack_head;
    size_t ack_count;
} dollybot_protocol_t;

// State starts zeroed for each SPP connection. False means the ACK queue is full.
bool dollybot_feed(dollybot_protocol_t *protocol, const uint8_t *data, size_t length, uint64_t now_ms);

// Returns the next ACK or due STATUS, or zero if there is nothing to send.
size_t dollybot_next_frame(dollybot_protocol_t *protocol, uint64_t now_ms,
                           uint8_t output[DOLLYBOT_STATUS_FRAME]);
