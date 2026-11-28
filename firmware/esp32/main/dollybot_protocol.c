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
};

enum {
    HELLO = 0x01,
    PING = 0x02,
    GIMBAL = 0x11,
    CALIBRATE = 0x20,
    TEACH_BEGIN = 0x30,
    TEACH_END = 0x31,
    TAKE_POINT = 0x32,
    ACK = 0x80,
    STATUS = 0x90,
    POINT = 0x91,
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

void dollybot_close_session(uint32_t session, dollybot_forward_fn forward)
{
    if (!session) return;
    uint8_t packet[14], empty = 0;
    size_t n = encode(packet, 0x7e, 0, session, &empty, 0);
    forward(packet, n, 0x7e, true);
}

static bool queue_response(dollybot_protocol_t *protocol, const uint8_t *data, size_t length)
{
    if (protocol->reply_count == DOLLYBOT_REPLY_QUEUE_SIZE) {
        return false;
    }

    size_t tail = (protocol->reply_head + protocol->reply_count) % DOLLYBOT_REPLY_QUEUE_SIZE;
    dollybot_response_t *response = &protocol->replies[tail];
    memcpy(response->data, data, length);
    response->length = length;
    ++protocol->reply_count;
    return true;
}

static bool queue_ack(dollybot_protocol_t *protocol, uint32_t session,
                      uint16_t seq, uint8_t type, uint8_t result)
{
    uint8_t payload[6] = {type, result};
    uint8_t frame[HEADER_SIZE + sizeof(payload) + CRC_SIZE];
    size_t length = encode(frame, ACK, seq, session, payload, sizeof(payload));
    return queue_response(protocol, frame, length);
}

static bool is_stm32_command(uint8_t type)
{
    return type == CALIBRATE || type == TEACH_BEGIN || type == TEACH_END || type == TAKE_POINT ||
           type == DOLLYBOT_STOP || (type >= 0x40 && type <= 0x45);
}

static uint8_t log_command(uint8_t type, uint16_t seq,
                           const uint8_t *payload, uint16_t length)
{
    switch (type) {
    case DOLLYBOT_MOTION: {
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
    case DOLLYBOT_STOP:
        if (length != 0) {
            return RESULT_BAD_ARGUMENT;
        }
        printf("STOP seq=%u\n", (unsigned)seq);
        return RESULT_OK;
    default:
        return RESULT_UNSUPPORTED;
    }
}

static bool handle_frame(dollybot_protocol_t *protocol, const uint8_t *frame, uint16_t length,
                         dollybot_forward_fn forward)
{
    uint8_t type = frame[3];
    uint16_t seq = read_u16(frame + 4);
    uint32_t session = read_u32(frame + 8);
    if (type != HELLO && (protocol->session == 0 || session != protocol->session)) {
        return true;
    }

    uint8_t result = RESULT_OK;
    bool new_session = false;
    if (frame[2] != VERSION) {
        result = RESULT_UNSUPPORTED;
    } else if (type == HELLO) {
        if (length != 0 || session == 0) {
            result = RESULT_BAD_ARGUMENT;
        } else if (session != protocol->session) {
            new_session = true;
            protocol->session = session;
            protocol->reply_head = 0;
            protocol->reply_count = 0;
            protocol->stm32_rx.length = 0;
        }
    } else if (type == DOLLYBOT_STOP && length != 0) {
        result = RESULT_BAD_ARGUMENT;
    } else if (is_stm32_command(type) && forward != NULL) {
        forward(frame, HEADER_SIZE + length + CRC_SIZE, type, false);
        return true;
    } else if (type == PING) {
        if (length != 0) {
            result = RESULT_BAD_ARGUMENT;
        }
    } else {
        result = log_command(type, seq, frame + HEADER_SIZE, length);
    }

    bool streaming = type == DOLLYBOT_MOTION || type == GIMBAL;
    if ((!streaming || result != RESULT_OK) && !queue_ack(protocol, session, seq, type, result)) {
        return false;
    }

    // HELLO and PING describe the wireless link. STOP is acknowledged by the motor MCU.
    if (forward != NULL && frame[2] == VERSION && type < ACK && result == RESULT_OK) {
        forward(frame, HEADER_SIZE + length + CRC_SIZE, type, new_session);
    }
    return true;
}

static bool handle_stm32_frame(dollybot_protocol_t *protocol, const uint8_t *frame, uint16_t length)
{
    if (frame[2] != VERSION || protocol->session == 0 || read_u32(frame + 8) != protocol->session) {
        return true;
    }

    switch (frame[3]) {
    case STATUS:
        if (length != STATUS_PAYLOAD_SIZE) {
            return true;
        }
        break;
    case ACK:
        if (length != 6 || (!is_stm32_command(frame[HEADER_SIZE]) && frame[HEADER_SIZE] != DOLLYBOT_MOTION && frame[HEADER_SIZE] != GIMBAL)) {
            return true;
        }
        break;
    case POINT:
        if (length != 30) {
            return true;
        }
        break;
    default:
        return true;
    }

    return queue_response(protocol, frame, HEADER_SIZE + length + CRC_SIZE);
}

static void discard_rx(dollybot_stream_t *stream, size_t count)
{
    stream->length -= count;
    memmove(stream->data, stream->data + count, stream->length);
}

static bool feed_stream(dollybot_protocol_t *protocol, const uint8_t *data, size_t length,
                        dollybot_forward_fn forward, bool from_stm32)
{
    dollybot_stream_t *stream = from_stm32 ? &protocol->stm32_rx : &protocol->app_rx;
    for (size_t i = 0; i < length; ++i) {
        stream->data[stream->length++] = data[i];
        while (stream->length != 0) {
            const uint8_t *frame = stream->data;
            if (frame[0] != MAGIC_FIRST || (stream->length >= 2 && frame[1] != MAGIC_SECOND)) {
                discard_rx(stream, 1);
                continue;
            }
            if (stream->length < HEADER_SIZE) {
                break;
            }
            uint16_t payload_length = read_u16(frame + 6);
            if (payload_length > MAX_PAYLOAD) {
                discard_rx(stream, 1);
                continue;
            }
            size_t frame_length = HEADER_SIZE + payload_length + CRC_SIZE;
            if (stream->length < frame_length) {
                break;
            }
            if (crc16(frame + 2, frame_length - 2 - CRC_SIZE) != read_u16(frame + frame_length - CRC_SIZE)) {
                discard_rx(stream, 1);
                continue;
            }

            bool accepted = from_stm32 ? handle_stm32_frame(protocol, frame, payload_length)
                                       : handle_frame(protocol, frame, payload_length, forward);
            discard_rx(stream, frame_length);
            if (!accepted) {
                return false;
            }
        }
    }
    return true;
}

bool dollybot_feed(dollybot_protocol_t *protocol, const uint8_t *data, size_t length,
                   dollybot_forward_fn forward)
{
    return feed_stream(protocol, data, length, forward, false);
}

bool dollybot_feed_stm32(dollybot_protocol_t *protocol, const uint8_t *data, size_t length)
{
    return feed_stream(protocol, data, length, NULL, true);
}

size_t dollybot_next_frame(dollybot_protocol_t *protocol,
                           uint8_t output[DOLLYBOT_STATUS_FRAME])
{
    if (protocol->reply_count != 0) {
        const dollybot_response_t *response = &protocol->replies[protocol->reply_head];
        memcpy(output, response->data, response->length);
        protocol->reply_head = (protocol->reply_head + 1) % DOLLYBOT_REPLY_QUEUE_SIZE;
        --protocol->reply_count;
        return response->length;
    }

    return 0;
}
