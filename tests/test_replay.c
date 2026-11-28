#include "vehicle.h"
#include "task_store.h"
#include "wire.h"
#include "dollybot_protocol.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
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
static bool drive;
static uint8_t last_status[34], last_ack[6];
static uint16_t seq;
static Decoder uart;
static dollybot_protocol_t bridge;
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
    (void)enable;
}
uint32_t board_now(void) { return now; }
uint32_t board_frame_id(void) { return ++frame; }
void board_send(const uint8_t *p, unsigned n) { assert(dollybot_feed_stm32(&bridge, p, n)); }
static void forward(const uint8_t *p, size_t n, uint8_t type, bool fresh) {
    (void)type;
    (void)fresh;
    for (size_t i = 0; i < n; ++i)
        wire_feed(&uart, p[i], vehicle_frame);
}
static void drain(void) {
    uint8_t out[48];
    while (dollybot_next_frame(&bridge, out)) {
        if (out[3] == ACK)
            memcpy(last_ack, out + 12, 6);
        else if (out[3] == STATUS)
            memcpy(last_status, out + 12, 34);
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
static void tick(unsigned steps) {
    Sensors s = {.valid = true, .accel_g = 1, .battery_v = 12};
    for (unsigned i = 0; i < steps; ++i) {
        now += 5;
        vehicle_tick(s, 5);
        drain();
    }
}
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
    tick(50);
    assert(drive);
    request(LEASE, token, 4);
    uint32_t end = now + 500;
    tick(60);
    request(PING, NULL, 0);
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
int main(void) {
    test_storage();
    test_kinematics();
    test_player();
    test_transport();
    puts("PASS: odometry, replay, 180 s storage, power loss, SPP/UART, retries, stop, lease and "
         "teaching");
    return 0;
}
