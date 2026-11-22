#include "dollybot_protocol.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

enum {
    MAGIC_FIRST = 0xA5,
    MAGIC_SECOND = 0x5A,
    VERSION = 1,
    HEADER_SIZE = 12,
    CRC_SIZE = 2,
    MAX_PAYLOAD = DOLLYBOT_MAX_FRAME - HEADER_SIZE - CRC_SIZE,
    STATUS_PAYLOAD_SIZE = 34,
    STATUS_INTERVAL_MS = 200,
};

enum {
    HELLO = 0x01,
    PING = 0x02,
    MOTION = 0x10,
    GIMBAL = 0x11,
    STOP = 0x12,
    CALIBRATE = 0x20,
    TEACH_BEGIN = 0x30,
    TEACH_END = 0x31,
    TAKE_POINT = 0x32,
    ACK = 0x80,
    STATUS = 0x90,
};

enum {
    RESULT_OK = 0,
    RESULT_BAD_ARGUMENT = 3,
    RESULT_UNSUPPORTED = 5,
};

static uint16_t read_u16(const uint8_t *data)
{
    return (uint16_t)(data[0] | ((uint16_t)data[1] << 8));
}

static int read_i16(const uint8_t *data)
{
    int value = read_u16(data);
    return value < 0x8000 ? value : value - 0x10000;
}

static uint32_t read_u32(const uint8_t *data)
{
    return read_u16(data) | ((uint32_t)read_u16(data + 2) << 16);
}

static void write_u16(uint8_t *data, uint16_t value)
{
    data[0] = (uint8_t)value;
    data[1] = (uint8_t)(value >> 8);
}

static void write_u32(uint8_t *data, uint32_t value)
{
    write_u16(data, (uint16_t)value);
    write_u16(data + 2, (uint16_t)(value >> 16));
}

static uint16_t crc16(const uint8_t *data, size_t length)
{
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < length; ++i) {
        crc ^= (uint16_t)((uint16_t)data[i] << 8);
        for (unsigned bit = 0; bit < 8; ++bit) {
            crc = (uint16_t)((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0));
        }
    }
    return crc;
}

static size_t encode(uint8_t *out, uint8_t type, uint16_t seq, uint32_t session,
                     const uint8_t *payload, uint16_t length)
{
    out[0] = MAGIC_FIRST;
    out[1] = MAGIC_SECOND;
    out[2] = VERSION;
    out[3] = type;
    write_u16(out + 4, seq);
    write_u16(out + 6, length);
    write_u32(out + 8, session);
    memcpy(out + HEADER_SIZE, payload, length);
    write_u16(out + HEADER_SIZE + length, crc16(out + 2, HEADER_SIZE + length - 2));
    return HEADER_SIZE + length + CRC_SIZE;
}

static bool queue_ack(dollybot_protocol_t *protocol, uint32_t session,
                      uint16_t seq, uint8_t type, uint8_t result)
{
    if (protocol->ack_count == DOLLYBOT_ACK_QUEUE_SIZE) {
        return false;
    }

    size_t tail = (protocol->ack_head + protocol->ack_count) % DOLLYBOT_ACK_QUEUE_SIZE;
    protocol->acks[tail] = (dollybot_ack_t){
        .session = session,
        .seq = seq,
        .request_type = type,
        .result = result,
    };
    ++protocol->ack_count;
    return true;
}

static uint8_t log_command(dollybot_protocol_t *protocol, uint8_t type, uint16_t seq,
                           const uint8_t *payload, uint16_t length)
{
    switch (type) {
    case MOTION: {
        if (length != 6) {
            return RESULT_BAD_ARGUMENT;
        }
        int strafe = read_i16(payload);
        int forward = read_i16(payload + 2);
        int rotation = read_i16(payload + 4);
        if (strafe < -1000 || strafe > 1000 || forward < -1000 || forward > 1000 ||
            rotation < -1000 || rotation > 1000) {
            return RESULT_BAD_ARGUMENT;
        }
        printf("MOTION seq=%u strafe=%d forward=%d rotation=%d\n",
               (unsigned)seq, strafe, forward, rotation);
        protocol->last_motion_seq = seq;
        return RESULT_OK;
    }
    case GIMBAL: {
        if (length != 4) {
            return RESULT_BAD_ARGUMENT;
        }
        int yaw = read_i16(payload);
        int pitch = read_i16(payload + 2);
        if (yaw < -18000 || yaw > 18000 || pitch < -18000 || pitch > 18000) {
            return RESULT_BAD_ARGUMENT;
        }
        printf("GIMBAL seq=%u yaw_cdeg=%d pitch_cdeg=%d\n", (unsigned)seq, yaw, pitch);
        return RESULT_OK;
    }
    case STOP:
    case CALIBRATE:
        if (length != 0) {
            return RESULT_BAD_ARGUMENT;
        }
        printf("%s seq=%u\n", type == STOP ? "STOP" : "CALIBRATE", (unsigned)seq);
        return type == STOP ? RESULT_OK : RESULT_UNSUPPORTED;
    case TEACH_BEGIN:
        if (length != 5 || payload[0] < 1 || payload[0] > 3) {
            return RESULT_BAD_ARGUMENT;
        }
        printf("TEACH_BEGIN seq=%u mode=%u frame_id=%" PRIu32 "\n",
               (unsigned)seq, (unsigned)payload[0], read_u32(payload + 1));
        return RESULT_UNSUPPORTED;
    case TEACH_END:
    case TAKE_POINT:
        if (length != 4) {
            return RESULT_BAD_ARGUMENT;
        }
        printf("%s seq=%u teach_id=%" PRIu32 "\n",
               type == TEACH_END ? "TEACH_END" : "TAKE_POINT", (unsigned)seq, read_u32(payload));
        return RESULT_UNSUPPORTED;
    default:
        return RESULT_UNSUPPORTED;
    }
}

static bool handle_frame(dollybot_protocol_t *protocol, uint16_t length, uint64_t now_ms)
{
    const uint8_t *frame = protocol->rx;
    uint8_t type = frame[3];
    uint16_t seq = read_u16(frame + 4);
    uint32_t session = read_u32(frame + 8);
    if (type != HELLO && (protocol->session == 0 || session != protocol->session)) {
        return true;
    }

    uint8_t result = RESULT_OK;
    if (frame[2] != VERSION) {
        result = RESULT_UNSUPPORTED;
    } else if (type == HELLO) {
        if (length != 0 || session == 0) {
            result = RESULT_BAD_ARGUMENT;
        } else if (session != protocol->session) {
            protocol->session = session;
            protocol->started_ms = now_ms;
            protocol->next_status_ms = now_ms;
            protocol->status_seq = 0;
            protocol->last_motion_seq = 0;
            protocol->ack_head = 0;
            protocol->ack_count = 0;
        }
    } else if (type == PING) {
        if (length != 0) {
            result = RESULT_BAD_ARGUMENT;
        }
    } else {
        result = log_command(protocol, type, seq, frame + HEADER_SIZE, length);
        if (result == RESULT_OK && (type == MOTION || type == GIMBAL)) {
            return true;
        }
    }
    return queue_ack(protocol, session, seq, type, result);
}

static void discard_rx(dollybot_protocol_t *protocol, size_t count)
{
    protocol->rx_length -= count;
    memmove(protocol->rx, protocol->rx + count, protocol->rx_length);
}

bool dollybot_feed(dollybot_protocol_t *protocol, const uint8_t *data, size_t length, uint64_t now_ms)
{
    for (size_t i = 0; i < length; ++i) {
        protocol->rx[protocol->rx_length++] = data[i];
        while (protocol->rx_length != 0) {
            const uint8_t *frame = protocol->rx;
            if (frame[0] != MAGIC_FIRST ||
                (protocol->rx_length >= 2 && frame[1] != MAGIC_SECOND)) {
                discard_rx(protocol, 1);
                continue;
            }
            if (protocol->rx_length < HEADER_SIZE) {
                break;
            }
            uint16_t payload_length = read_u16(frame + 6);
            if (payload_length > MAX_PAYLOAD) {
                discard_rx(protocol, 1);
                continue;
            }
            size_t frame_length = HEADER_SIZE + payload_length + CRC_SIZE;
            if (protocol->rx_length < frame_length) {
                break;
            }
            if (crc16(frame + 2, frame_length - 2 - CRC_SIZE) != read_u16(frame + frame_length - CRC_SIZE)) {
                discard_rx(protocol, 1);
                continue;
            }
            bool accepted = handle_frame(protocol, payload_length, now_ms);
            discard_rx(protocol, frame_length);
            if (!accepted) {
                return false;
            }
        }
    }
    return true;
}

size_t dollybot_next_frame(dollybot_protocol_t *protocol, uint64_t now_ms,
                           uint8_t output[DOLLYBOT_STATUS_FRAME])
{
    if (protocol->ack_count != 0) {
        dollybot_ack_t ack = protocol->acks[protocol->ack_head];
        protocol->ack_head = (protocol->ack_head + 1) % DOLLYBOT_ACK_QUEUE_SIZE;
        --protocol->ack_count;
        uint8_t payload[6] = {ack.request_type, ack.result};
        return encode(output, ACK, ack.seq, ack.session, payload, sizeof(payload));
    }

    if (protocol->session == 0 || now_ms < protocol->next_status_ms) {
        return 0;
    }

    // Idle, uncalibrated placeholder pose while no hardware telemetry is available.
    uint8_t payload[STATUS_PAYLOAD_SIZE] = {0};
    write_u32(payload, (uint32_t)(now_ms - protocol->started_ms));
    write_u16(payload + 32, protocol->last_motion_seq);
    protocol->next_status_ms = now_ms + STATUS_INTERVAL_MS;
    return encode(output, STATUS, ++protocol->status_seq, protocol->session, payload, sizeof(payload));
}
