#include "vehicle.h"
#include "power_config.h"
#include "task_store.h"
#include "wire.h"
#include "dollybot_protocol.h"
#include "vofa.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint8_t flash[2][SLOT_BYTES];
static int writes_before_failure = -1;
const uint8_t *slot_address(unsigned slot) { return flash[slot]; }
bool slot_erase(unsigned slot) {
    memset(flash[slot], 0xff, SLOT_BYTES);
    return true;
}
bool slot_program(unsigned slot, unsigned offset, const void *data, unsigned n) {
    if (writes_before_failure == 0)
        return false;
    if (writes_before_failure > 0)
        --writes_before_failure;
    memcpy(flash[slot] + offset, data, n);
    return true;
}
const Geometry geometry = {0.25f, 500, 600, .0018f, .002f, {1, 1, 1, 1}};
static uint32_t now, frame, last_deadline;
static bool drive, servo;
static uint8_t last_status[34], last_ack[6], last_power[POWER_STATUS_SIZE];
static const Sensors nominal_power = {
    .valid = true, .power_valid = true, .accel_g = 1, .battery_v = 12
};
static uint16_t seq;
static Decoder uart;
static unsigned tuning_frames;
static float channels[TUNE_MAX_CHANNELS];
static uint8_t captured_mode;
static void capture_tuning(const uint8_t *payload, size_t payload_length) {
    char line[VOFA_LINE_CAPACITY];
    size_t length = vofa_format(line, sizeof line, payload, payload_length);
    unsigned count = tuning_channel_count(payload[0]);
    captured_mode = payload[0];
    assert(length > 5 && line[length - 1] == '\n' && strncmp(line, "tune:", 5) == 0);
    const char *next = line + 5;
    for (unsigned i = 0; i < count; ++i) {
        char *end;
        channels[i] = strtof(next, &end);
        assert(end != next && *end == (i + 1 == count ? '\n' : ','));
        next = end + 1;
    }
    assert(next == line + length);
    ++tuning_frames;
}
static dollybot_protocol_t bridge = {.tuning = capture_tuning};
static unsigned motor_calls;
static uint32_t session = 123;
void board_stop(void) { drive = false; }
void board_drive(const float duty[4], uint32_t until) {
    drive = true;
    ++motor_calls;
    last_deadline = until;
    for (unsigned i = 0; i < 4; ++i)
        assert(fabsf(duty[i]) <= 1.00001f);
}
void board_servo(float *pan, float *tilt, bool enable) {
    (void)pan;
    (void)tilt;
    servo = enable;
}
uint32_t board_now(void) { return now; }
uint32_t board_frame_id(void) { return ++frame; }
void board_send(const uint8_t *p, unsigned n) { assert(dollybot_feed_stm32(&bridge, p, n)); }
void board_send_tuning(const uint8_t *p, unsigned n) {
    assert(p[3] == DOLLY_TUNING_TYPE && u32(p + 8) == 0);
    assert(n == tuning_payload_size(p[12]) + 14);
    assert(dollybot_feed_stm32(&bridge, p, 7));
    assert(dollybot_feed_stm32(&bridge, p + 7, n - 7));
}
static void forward(const uint8_t *p, size_t n, uint8_t type, bool fresh) {
    (void)type;
    (void)fresh;
    for (size_t i = 0; i < n; ++i)
        wire_feed(&uart, p[i], vehicle_frame);
}
static void select_tuning(uint8_t mode) {
    uint8_t out[DOLLY_TUNING_SELECT_FRAME_SIZE];
    size_t n = dollybot_encode_tuning_selection(out, mode);
    assert(n == sizeof(out));
    forward(out, n, DOLLY_TUNING_SELECT, false);
}
static void drain(void) {
    uint8_t out[48];
    while (dollybot_next_frame(&bridge, out)) {
        if (out[3] == ACK)
            memcpy(last_ack, out + 12, 6);
        else if (out[3] == STATUS)
            memcpy(last_status, out + 12, 34);
        else if (out[3] == POWER_STATUS) {
            assert(u16(out + 6) == POWER_STATUS_SIZE);
            memcpy(last_power, out + 12, sizeof last_power);
        }
    }
}
static void request(uint8_t type, const uint8_t *data, unsigned n) {
    uint8_t out[270];
    size_t size = wire_encode(out, type, ++seq, session, data, n);
    memset(last_ack, 0xff, 6);
    assert(dollybot_feed(&bridge, out, 4, forward));
    assert(dollybot_feed(&bridge, out + 4, size - 4, forward));
    drain();
}
static void sample(Sensors s, unsigned elapsed) {
    now += elapsed;
    vehicle_tick(s, elapsed);
    drain();
}
static void tick_with(Sensors s, unsigned steps) {
    for (unsigned i = 0; i < steps; ++i)
        sample(s, 5);
}
static void tick(unsigned steps) { tick_with(nominal_power, steps); }
static void ack(uint8_t type, uint8_t result) {
    assert(last_ack[0] == type && last_ack[1] == result);
}
static void make_point(uint8_t *p, uint32_t time, uint32_t id, int x) {
    memset(p, 0, 22);
    put32(p, time);
    put32(p + 4, id);
    put32(p + 8, (uint32_t)x);
}
static void test_storage(void) {
    memset(flash, 0xff, sizeof flash);
    store_init();
    assert(store_begin(1, 0, 1, 3601));
    assert(store_chunk(0, (const uint8_t *)"A", 1));
    uint8_t point[22];
    for (unsigned i = 0; i <= 3600; ++i) {
        make_point(point, i * 50, 7, (int)i);
        assert(store_chunk(1 + 22 * i, point, 22));
    }
    assert(store_commit());
    store_init();
    assert(store_count() == 3601);
    assert(store_read(3600).time == 180000);
    for (int fail = 0; fail < 3; ++fail) {
        writes_before_failure = fail;
        assert(store_begin(1, 0, 1, 1));
        assert(store_chunk(0, (const uint8_t *)"B", 1));
        make_point(point, 20, 5, -42);
        assert(store_chunk(1, point, 22));
        assert(!store_commit());
        store_init();
        assert(store_count() == 3601 && store_read(3000).pose.x == 3000);
    }
    writes_before_failure = -1;
}
static void test_kinematics(void) {
    Chassis c = {0};
    estimate(&c, &geometry, (int16_t[]){1, 1, 1, 1}, 0, .005f);
    assert(c.pose.y == 1 && c.pose.x == 0);
    estimate(&c, &geometry, (int16_t[]){1, -1, -1, 1}, 0, .005f);
    assert(c.pose.x == 1);
    c.pose.yaw = 90;
    estimate(&c, &geometry, (int16_t[]){1, 1, 1, 1}, 0, .005f);
    assert(fabsf(c.pose.x - 2) < .0001f);
    estimate(&c, &geometry, (int16_t[]){2, -2, 2, -2}, 20, .005f);
    assert(fabsf(c.pose.yaw - 90.1f) < .001f);
    assert(angle_delta(-179, 179) == 2);

    Geometry calibrated = geometry;
    calibrated.odometry_scale = 0.035f;
    Chassis mixed = {0};
    const int16_t counts[4] = {2000, -400, 100, 1300};
    estimate(&mixed, &calibrated, counts, 0, .005f);
    /* Reference: per-wheel millimetres, followed by the mecanum average. */
    float wheel_mm[4];
    for (unsigned i = 0; i < 4; ++i) {
        wheel_mm[i] = counts[i] * (4.0f * calibrated.odometry_scale);
        assert(fabsf(mixed.wheel_speed[i] - .25f * wheel_mm[i] / .005f) < .01f);
    }
    assert(fabsf(mixed.pose.y - (wheel_mm[0] + wheel_mm[1] + wheel_mm[2] + wheel_mm[3]) / 4) <
           .001f);
    assert(fabsf(mixed.pose.x - (wheel_mm[0] - wheel_mm[1] - wheel_mm[2] + wheel_mm[3]) / 4) <
           .001f);
}
static Sample route(unsigned i) {
    return (Sample){.time = i * 100, .frame = 1, .pose = {.x = i * 5.0f, .pan = i * 10.0f}};
}
static void test_player(void) {
    Chassis c = {0};
    Player player;
    float pan = 0, tilt = 0;
    player_start(&player, 2, 0);
    unsigned returns = 0;
    Phase previous = IDLE;
    for (unsigned i = 0; i < 8000 && player.phase != COMPLETE; ++i) {
        if (previous != RETURNING && player.phase == RETURNING)
            ++returns;
        previous = player.phase;
        Twist v = player_tick(&player, &c, 5, 3, route, &pan, &tilt);
        assert(player.phase != TRACK_FAULT);
        c.pose.x += v.right * .005f;
        c.pose.y += v.forward * .005f;
        c.pose.yaw += v.turn * .005f;
    }
    assert(player.phase == COMPLETE && returns == 2 && pan == 20);
    c.pose.x = 200;
    player_start(&player, 1, 0);
    for (unsigned i = 0; i < 1000 && player.phase != TRACK_FAULT; ++i)
        player_tick(&player, &c, 5, 3, route, &pan, &tilt);
    assert(player.phase == TRACK_FAULT);
}
static void test_transport(void) {
    vehicle_init();
    last_status[23] = 0xff;
    request(HELLO, NULL, 0);
    assert(last_status[23] == 0xff);
    tick(150);
    request(CALIBRATE, NULL, 0);
    ack(CALIBRATE, 0);
    uint32_t id = u32(last_ack + 2);
    request(CAPABILITIES, NULL, 0);
    ack(CAPABILITIES, 0);
    assert(u32(last_ack + 2) & 1);
    uint8_t begin[10] = {1, 0}, data[49] = {0}, play[6] = {0};
    put32(begin + 2, 1);
    put32(begin + 6, 2);
    request(UPLOAD_BEGIN, begin, 10);
    ack(UPLOAD_BEGIN, 0);
    data[4] = 'A';
    make_point(data + 5, 1000, id, 10);
    make_point(data + 27, 1100, id, 20);
    request(UPLOAD_DATA, data, sizeof data);
    ack(UPLOAD_DATA, 0);
    --seq;
    request(UPLOAD_DATA, data, sizeof data);
    ack(UPLOAD_DATA, 0);
    request(COMMIT, NULL, 0);
    ack(COMMIT, 0);
    assert(u32(last_ack + 2) == 2 && motor_calls == 0);
    --seq;
    request(COMMIT, NULL, 0);
    ack(COMMIT, 0);
    put32(play, id);
    play[4] = 1;
    request(START, play, 6);
    ack(START, 0);
    uint8_t token[4];
    put32(token, u32(last_ack + 2));
    select_tuning(TUNE_ANGLE);
    tick(50);
    assert(captured_mode == TUNE_ANGLE && channels[TUNE_DATA + 3] == PLAYING);
    assert(drive);
    unsigned flags = (unsigned)channels[TUNE_FLAGS];
    assert((flags & TUNE_REPLAY_YAW_ACTIVE) && (flags & TUNE_DRIVE_AUTHORIZED));
    request(LEASE, token, 4);
    uint32_t end = now + 500;
    tick(60);
    request(PING, NULL, 0);
    select_tuning(TUNE_ANGLE);
    assert(last_deadline == end);
    tick(40);
    assert(now == end && !drive);
    request(LEASE, token, 4);
    tick(1);
    assert(!drive);
    request(START, play, 6);
    ack(START, 0);
    tick(1);
    assert(drive);
    request(STOP, NULL, 0);
    ack(STOP, 0);
    tick(1);
    assert(!drive);
    request(START, play, 6);
    ack(START, 0);
    tick(1);
    ++session;
    request(HELLO, NULL, 0);
    assert(!drive);
    uint8_t teach[5] = {3};
    put32(teach + 1, id);
    request(TEACH_BEGIN, teach, 5);
    ack(TEACH_BEGIN, 0);
    uint8_t teachid[4];
    put32(teachid, u32(last_ack + 2));
    tick(20);
    assert(!drive && last_status[22] == 3);
    request(TEACH_END, teachid, 4);
    ack(TEACH_END, 0);
    assert(u32(last_ack + 2) >= 3);
}
static uint32_t connect_calibrated(void) {
    ++session;
    request(HELLO, NULL, 0);
    tick(150);
    request(CALIBRATE, NULL, 0);
    ack(CALIBRATE, 0);
    return u32(last_ack + 2);
}

static void test_power_protection(void) {
    uint8_t motion[6] = {0};
    put16(motion + 2, 100);
    const uint8_t center[4] = {0};
    vehicle_init();
    connect_calibrated();
    assert(u16(last_power) == 12000 && last_power[8] == 80 && last_power[9] == 0);

    request(MOTION, motion, sizeof motion);
    request(GIMBAL, center, sizeof center);
    Sensors overloaded = nominal_power;
    overloaded.motor_a = MOTOR_OVERCURRENT_A;
    overloaded.gimbal_a = 1.25f;
    tick_with(overloaded, MOTOR_OVERCURRENT_MS / 5 - 1);
    assert(drive && servo && last_power[9] == 0);
    tick(1);
    /* Keep sampling through acceptable control-loop jitter. */
    for (unsigned i = 0; i < 6; ++i)
        sample(overloaded, 15);
    sample(overloaded, 5);
    assert(drive && servo);
    unsigned calls = motor_calls;
    tick_with(overloaded, 1);
    assert(!drive && !servo && motor_calls == calls);
    assert(last_power[9] == POWER_MOTOR_TRIP && u32(last_power + 2) == 8000 &&
           u16(last_power + 6) == 1250);

    request(STOP, NULL, 0);
    request(PING, NULL, 0);
    ++session;
    request(HELLO, NULL, 0);
    tick(1);
    request(MOTION, motion, sizeof motion);
    request(GIMBAL, center, sizeof center);
    tick(1);
    assert(last_power[9] == POWER_MOTOR_TRIP && !drive && !servo);

    vehicle_init();
    uint32_t id = connect_calibrated();
    uint8_t play[6] = {0};
    put32(play, id);
    play[4] = play[5] = 1;
    request(START, play, sizeof play);
    ack(START, 0);
    uint8_t lease[4];
    put32(lease, u32(last_ack + 2));
    overloaded = nominal_power;
    overloaded.gimbal_a = GIMBAL_OVERCURRENT_A;
    tick_with(overloaded, 50);
    request(LEASE, lease, sizeof lease);
    tick_with(overloaded, GIMBAL_OVERCURRENT_MS / 5 - 51);
    assert(drive && servo && last_power[9] == 0);
    calls = motor_calls;
    tick_with(overloaded, 1);
    assert(!drive && !servo && motor_calls == calls && last_power[9] == POWER_GIMBAL_TRIP);
    request(LEASE, lease, sizeof lease);
    tick(10);
    assert(last_status[23] >> 5 == TRACK_FAULT && (last_status[23] & 16));
    request(MOTION, motion, sizeof motion);
    request(GIMBAL, center, sizeof center);
    tick(1);
    assert(!drive && !servo);

    vehicle_init();
    connect_calibrated();
    request(MOTION, motion, sizeof motion);
    request(GIMBAL, center, sizeof center);
    tick(1);
    assert(drive && servo);
    Sensors invalid = nominal_power;
    invalid.power_valid = false;
    sample(invalid, 5);
    assert(!drive && !servo && last_power[9] == POWER_ADC_FAULT);
    assert(u16(last_power) == UINT16_MAX && u32(last_power + 2) == UINT32_MAX &&
           u16(last_power + 6) == UINT16_MAX && last_power[8] == UINT8_MAX);
    tick(40);
    request(MOTION, motion, sizeof motion);
    tick(1);
    assert(!drive && last_power[9] == POWER_ADC_FAULT && u16(last_power) == 12000);

    vehicle_init();
    ++session;
    request(HELLO, NULL, 0);
    Sensors low = nominal_power;
    low.battery_v = 9.4f;
    sample(low, 5);
    assert(u16(last_power) == 9400 && last_power[8] == 0 && last_power[9] == POWER_BATTERY_RANGE);
    tick(40);
    assert(last_power[9] == 0);
}

static void test_vofa_commands(void) {
    vofa_command_t command = {0};
    uint8_t mode = TUNE_OFF;
    const uint8_t input[] = "SPEEDtooLong\nANGLE\r\nRATE\nODOM\nSPEED\nOFF\n";
    const uint8_t expected[] = {TUNE_ANGLE, TUNE_RATE, TUNE_ODOM, TUNE_SPEED, TUNE_OFF};
    unsigned received = 0;
    for (unsigned i = 0; i < sizeof(input) - 1; ++i) {
        if (vofa_command_byte(&command, input[i], &mode)) {
            assert(received < sizeof(expected) && mode == expected[received++]);
        }
    }
    assert(received == sizeof(expected));
}

static void test_vofa_tuning(void) {
    vehicle_init();
    bridge.session = 0;
    Sensors s = nominal_power;
    s.encoder_total[0] = 100;
    s.encoder_total[1] = -200;
    s.encoder_total[2] = 300;
    s.encoder_total[3] = 400;
    s.clockwise_dps = -12.5f;
    unsigned before = tuning_frames;
    tick_with(s, 10);
    assert(tuning_frames == before && bridge.reply_count == 0);
    select_tuning(TUNE_ODOM);
    tick_with(s, 1);
    assert(captured_mode == TUNE_ODOM && tuning_frames == before + 1 && bridge.reply_count == 0);
    assert(channels[TUNE_DATA + 1] == -200 && channels[TUNE_DATA + 4] == 600);
    assert(channels[TUNE_DATA + 7] == geometry.odometry_scale);
    tick_with(s, 3);
    assert(tuning_frames == before + 1);
    tick_with(s, 1);
    assert(tuning_frames == before + 2 && channels[TUNE_TIME] == (float)now);

    uint8_t packet[TUNE_MAX_PAYLOAD + 14];
    uint8_t payload[TUNE_MAX_PAYLOAD] = {TUNE_RATE};
    unsigned len = tuning_payload_size(TUNE_RATE);
    unsigned length = (unsigned)wire_encode(packet, DOLLY_TUNING_TYPE, 1, 55, payload, len);
    assert(dollybot_feed_stm32(&bridge, packet, length));
    length = (unsigned)wire_encode(packet, DOLLY_TUNING_TYPE, 2, 0, payload, len - 4);
    assert(dollybot_feed_stm32(&bridge, packet, length));
    length = (unsigned)wire_encode(packet, DOLLY_TUNING_TYPE, 3, 0, payload, len);
    packet[length - 1] ^= 1;
    assert(dollybot_feed_stm32(&bridge, packet, length));
    assert(tuning_frames == before + 2 && bridge.reply_count == 0);
    char small[6];
    assert(vofa_format(small, sizeof small, payload, len) == 0);
    put32(payload + 1, 0x7f800000);
    char line[VOFA_LINE_CAPACITY];
    assert(vofa_format(line, sizeof line, payload, len) == 0);

    connect_calibrated();
    uint8_t motion[6] = {0};
    put16(motion + 2, 250);
    put16(motion + 4, 300);
    request(MOTION, motion, sizeof motion);
    select_tuning(TUNE_RATE);
    s.counts[0] = 1;
    tick_with(s, 4);
    assert(captured_mode == TUNE_RATE && drive && ((unsigned)channels[TUNE_FLAGS] & TUNE_DRIVE_AUTHORIZED));
    assert(!((unsigned)channels[TUNE_FLAGS] & TUNE_REPLAY_YAW_ACTIVE));
    assert(fabsf(channels[TUNE_DATA] - 27) < .0001f && channels[TUNE_DATA + 1] == -12.5f);
    assert(channels[TUNE_DATA + 2] > 0);

    uint8_t forged = TUNE_ANGLE;
    request(DOLLY_TUNING_SELECT, &forged, 1);
    tick_with(s, 4);
    assert(captured_mode == TUNE_RATE);

    before = tuning_frames;
    select_tuning(TUNE_SPEED);
    tick_with(s, 1);
    assert(captured_mode == TUNE_SPEED && tuning_frames == before + 1);
    assert(channels[TUNE_DATA] > 0 && channels[TUNE_DATA + 4] > 0 && channels[TUNE_DATA + 8] != 0);
    request(STOP, NULL, 0);
    tick(4);
    assert(!drive && channels[TUNE_DATA] == 0 && channels[TUNE_DATA + 8] == 0);
    select_tuning(TUNE_OFF);
    before = tuning_frames;
    tick(8);
    assert(tuning_frames == before);

    select_tuning(TUNE_ODOM);
    tick(1);
    assert(tuning_frames > before);
    tick(TUNE_SELECTION_MS / 5);
    before = tuning_frames;
    tick(8);
    assert(tuning_frames == before);
}

int main(void) {
    test_storage();
    test_kinematics();
    test_player();
    test_transport();
    test_power_protection();
    test_vofa_commands();
    test_vofa_tuning();
    puts("PASS: odometry, replay, 180 s storage, power loss, SPP/UART, retries, stop, lease and "
         "teaching, power protection and VOFA+ telemetry");
    return 0;
}
