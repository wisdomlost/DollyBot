#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    DOLLYBOT_MAX_FRAME = 270,
    DOLLYBOT_STATUS_FRAME = 48,
    DOLLYBOT_REPLY_QUEUE_SIZE = 16,
    DOLLYBOT_MOTION = 0x10,
    DOLLYBOT_STOP = 0x12,
};

typedef struct {
    uint8_t data[DOLLYBOT_MAX_FRAME];
    size_t length;
} dollybot_stream_t;

typedef struct {
    uint8_t data[DOLLYBOT_STATUS_FRAME];
    size_t length;
} dollybot_response_t;

typedef struct {
    dollybot_stream_t app_rx;
    dollybot_stream_t stm32_rx;
    uint32_t session;
    uint64_t started_ms;
    uint64_t next_status_ms;
    uint16_t status_seq;
    uint16_t last_motion_seq;
    bool stm32_status_seen;
    dollybot_response_t replies[DOLLYBOT_REPLY_QUEUE_SIZE];
    size_t reply_head;
    size_t reply_count;
} dollybot_protocol_t;

// Called synchronously for a valid request. The frame bytes are borrowed during the call.
typedef void (*dollybot_forward_fn)(const uint8_t *frame, size_t length, uint8_t type, bool new_session);

// State starts zeroed for each SPP connection. False means the response queue is full.
bool dollybot_feed(dollybot_protocol_t *protocol, const uint8_t *data, size_t length, uint64_t now_ms,
                   dollybot_forward_fn forward);
bool dollybot_feed_stm32(dollybot_protocol_t *protocol, const uint8_t *data, size_t length);

// Returns the next response, or an idle status before the STM32 starts sending telemetry.
size_t dollybot_next_frame(dollybot_protocol_t *protocol, uint64_t now_ms,
                           uint8_t output[DOLLYBOT_STATUS_FRAME]);
