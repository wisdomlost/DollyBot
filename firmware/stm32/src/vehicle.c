#include "vehicle.h"
#include "wire.h"
#include "task_store.h"
#include <string.h>
#include <math.h>
#include <stdlib.h>

enum { OK, BAD_STATE, UNCALIBRATED, BAD_ARG, WRONG_FRAME, UNSUPPORTED };
static Chassis chassis;
static Player player;
static Twist manual;
static uint32_t session, started, last_contact, deadline, next_status, frame_id, teach_id,
    point_index;
static uint32_t run_id, last_sample, still_ms;
static uint16_t motion_seq, fence, status_seq;
static uint8_t teaching;
static bool authorized, calibrated, healthy, timed_out, voltage_ok, servo_ok, servo_requested;
static float bias, stationary_mean, requested_pan, requested_tilt;
static unsigned stationary_count;
static uint32_t servo_overload_ms;
typedef struct {
    uint16_t seq, crc;
    uint8_t type, len, frame[48];
} Reply;
static Reply cache[32];
static unsigned cache_index;
static bool resample_after_save;

static bool playing(void) { return player.phase >= RETURNING && player.phase <= PLAYING; }
static void halt(void) {
    board_stop();
    authorized = false;
    manual = (Twist){0};
    memset(chassis.integral, 0, sizeof chassis.integral);
    memset(chassis.setpoint, 0, sizeof chassis.setpoint);
    chassis.turn_integral = 0;
    if (playing())
        player.phase = IDLE;
    run_id = 0;
}
void vehicle_link_lost(void) {
    halt();
    store_abort();
    session = 0;
    teaching = 0;
    servo_requested = false;
    board_servo(&chassis.pose.pan, &chassis.pose.tilt, false);
    memset(cache, 0, sizeof cache);
}
void vehicle_init(void) {
    store_init();
    vehicle_link_lost();
    servo_ok = true;
}
static void emit(uint8_t type, uint16_t seq, const uint8_t *data, unsigned n, Reply *saved) {
    uint8_t packet[48];
    unsigned len = (unsigned)wire_encode(packet, type, seq, session, data, (uint16_t)n);
    if (saved) {
        saved->len = (uint8_t)len;
        memcpy(saved->frame, packet, len);
    }
    board_send(packet, len);
}
static void pack_pose(uint8_t *out) {
    put32(out, board_now() - started);
    put32(out + 4, frame_id);
    put32(out + 8, (uint32_t)(int32_t)lroundf(chassis.pose.x));
    put32(out + 12, (uint32_t)(int32_t)lroundf(chassis.pose.y));
    put16(out + 16, (uint16_t)(int16_t)lroundf(chassis.pose.yaw * 100));
    put16(out + 18, (uint16_t)(int16_t)lroundf(chassis.pose.pan * 100));
    put16(out + 20, (uint16_t)(int16_t)lroundf(chassis.pose.tilt * 100));
}
static void status(void) {
    uint8_t p[34];
    pack_pose(p);
    if (teaching == 1 || teaching == 3) {
        if (last_sample == u32(p) && point_index)
            return;
        ++point_index;
        last_sample = u32(p);
    }
    p[22] = teaching;
    p[23] = (uint8_t)(calibrated | timed_out << 1 | (!healthy) << 2 | (!voltage_ok) << 3 |
                      (!servo_ok) << 4 | player.phase << 5);
    put32(p + 24, teach_id);
    put32(p + 28, point_index);
    put16(p + 32, motion_seq);
    emit(STATUS, ++status_seq, p, sizeof p, NULL);
}
static bool later(uint16_t a, uint16_t b) { return (int16_t)(a - b) > 0; }
void vehicle_frame(const uint8_t *f) {
    uint32_t sid = u32(f + 8), now = board_now();
    unsigned n = u16(f + 6);
    uint16_t seq = u16(f + 4), crc = u16(f + 12 + n);
    uint8_t type = f[3], result = OK;
    const uint8_t *p = f + 12;
    if (f[2] != 1 || !sid)
        return;
    if (type == HELLO && !n && session != sid) {
        vehicle_link_lost();
        session = sid;
        started = now;
        fence = seq;
        next_status = now;
    }
    if (session != sid)
        return;
    if (type == 0x7e && !n) {
        vehicle_link_lost();
        return;
    }
    last_contact = now;
    if (type == HELLO || type == PING)
        return; /* ESP32 acknowledges these locally. */
    bool stream = type == MOTION || type == GIMBAL || type == LEASE;
    for (unsigned i = 0; !stream && i < 32; ++i) {
        if (cache[i].len && cache[i].seq == seq && cache[i].type == type) {
            if (cache[i].crc == crc)
                board_send(cache[i].frame, cache[i].len);
            return;
        }
    }
    uint32_t value = 0;
    bool sample_after = false;
    Reply *reply = NULL;
    if (!stream) {
        reply = &cache[cache_index++ % 32];
        *reply = (Reply){.seq = seq, .crc = crc, .type = type};
    }
    bool can_move = calibrated && healthy && voltage_ok && geometry_valid(&geometry);
    if (type != STOP && type != CAPABILITIES && !later(seq, fence))
        result = BAD_STATE;
    else
        switch (type) {
        case STOP:
            if (n) {
                result = BAD_ARG;
                break;
            }
            if (later(seq, fence))
                fence = seq;
            halt();
            store_abort();
            timed_out = false;
            requested_pan = chassis.pose.pan;
            requested_tilt = chassis.pose.tilt;
            break;
        case CAPABILITIES:
            if (n)
                result = BAD_ARG;
            else
                value = 1u | (store_count() ? 2u : 0u) | (geometry_valid(&geometry) ? 0u : 4u) |
                        (store_repeats() << 8);
            break;
        case CALIBRATE:
            if (n)
                result = BAD_ARG;
            else if (teaching || playing() || store_busy() || still_ms < 600 || !healthy)
                result = BAD_STATE;
            else {
                halt();
                bias = stationary_mean;
                frame_id = board_frame_id();
                calibrated = frame_id != 0;
                if (!calibrated) {
                    result = BAD_STATE;
                    break;
                }
                chassis.pose.x = chassis.pose.y = chassis.pose.yaw = 0;
                value = frame_id;
                player.phase = IDLE;
                sample_after = true;
            }
            break;
        case MOTION: {
            if (n != 6) {
                result = BAD_ARG;
                break;
            }
            int right = (int16_t)u16(p), forward = (int16_t)u16(p + 2), turn = (int16_t)u16(p + 4);
            if (right < -1000 || right > 1000 || forward < -1000 || forward > 1000 ||
                turn < -1000 || turn > 1000) {
                result = BAD_ARG;
                break;
            }
            if (!right && !forward && !turn) {
                if (!playing())
                    halt();
                fence = seq;
                return;
            }
            if (!can_move || playing() || teaching == 3 || store_busy()) {
                result = BAD_STATE;
                break;
            }
            manual = (Twist){right * .2f, forward * .2f, turn * .09f};
            motion_seq = fence = seq;
            authorized = true;
            deadline = now + 500;
            timed_out = false;
            return;
        }
        case GIMBAL:
            if (n != 4 || abs((int16_t)u16(p)) > 18000 || abs((int16_t)u16(p + 2)) > 18000) {
                result = BAD_ARG;
                break;
            }
            if (playing() || !servo_ok || !voltage_ok || store_busy()) {
                result = BAD_STATE;
                break;
            }
            requested_pan = (int16_t)u16(p) * .01f;
            requested_tilt = (int16_t)u16(p + 2) * .01f;
            servo_requested = true;
            return;
        case TEACH_BEGIN:
            if (n != 5 || p[0] < 1 || p[0] > 3) {
                result = BAD_ARG;
                break;
            }
            if (!can_move) {
                result = UNCALIBRATED;
                break;
            }
            if (u32(p + 1) != frame_id) {
                result = WRONG_FRAME;
                break;
            }
            if (teaching || playing() || store_busy()) {
                result = BAD_STATE;
                break;
            }
            if (p[0] == 3)
                halt();
            teaching = p[0];
            point_index = 0;
            value = ++teach_id;
            sample_after = true;
            break;
        case TEACH_END:
        case TAKE_POINT:
            if (n != 4 || !teaching || u32(p) != teach_id) {
                result = BAD_STATE;
                break;
            }
            if (type == TEACH_END) {
                if (teaching != 2)
                    status();
                value = point_index;
                teaching = 0;
                halt();
            } else if (teaching != 2)
                result = BAD_STATE;
            else {
                uint8_t point[30];
                put32(point, teach_id);
                put32(point + 4, ++point_index);
                pack_pose(point + 8);
                emit(POINT, seq, point, sizeof point, reply);
                return;
            }
            break;
        case UPLOAD_BEGIN:
            if (playing() || teaching || authorized) {
                result = BAD_STATE;
                break;
            }
            if ((n != 10 && n != 11) || (n == 11 && (p[10] < 1 || p[10] > 20)) ||
                !store_begin(p[0], p[1], u32(p + 2), u32(p + 6)))
                result = BAD_ARG;
            else if (n == 11)
                store_set_repeats(p[10]);
            break;
        case UPLOAD_DATA:
            if (n < 5 || !store_chunk(u32(p), p + 4, n - 4))
                result = BAD_ARG;
            break;
        case COMMIT:
            if (n || playing() || authorized || teaching || still_ms < 300 || !voltage_ok) {
                result = BAD_STATE;
                break;
            }
            halt();
            servo_requested = false;
            board_servo(&chassis.pose.pan, &chassis.pose.tilt, false);
            resample_after_save = true;
            if (!store_commit())
                result = BAD_ARG;
            else {
                value = store_count();
                player.phase = IDLE;
            }
            break;
        case START:
            /* Current calibration id and explicit physical alignment confirmation. */
            if (n != 6 || !p[4] || p[4] > 20 || p[5] > 1) {
                result = BAD_ARG;
                break;
            }
            if (!can_move || !servo_ok) {
                result = UNCALIBRATED;
                break;
            }
            if (teaching || playing() || authorized || store_busy() || !store_count() ||
                still_ms < 300) {
                result = BAD_STATE;
                break;
            }
            if (u32(p) != frame_id || (store_read(0).frame != frame_id && !p[5])) {
                result = WRONG_FRAME;
                break;
            }
            player_start(&player, p[4], chassis.pose.yaw);
            run_id = ((uint32_t)seq << 16) | 1;
            value = run_id;
            timed_out = false;
            deadline = now + 500;
            authorized = true;
            servo_requested = true;
            fence = seq;
            break;
        case LEASE:
            if (n == 4 && playing() && authorized && u32(p) == run_id &&
                (int32_t)(deadline - now) > 0) {
                deadline = now + 500;
                fence = seq;
            }
            return;
        default:
            result = UNSUPPORTED;
        }
    uint8_t ack[6] = {type, result};
    put32(ack + 2, value);
    emit(ACK, seq, ack, sizeof ack, reply);
    if (sample_after) {
        status();
        next_status = now + 50;
    }
}
void vehicle_tick(Sensors s, uint32_t ms) {
    uint32_t now = board_now();
    bool stationary_gap =
        resample_after_save && fabsf(s.clockwise_dps - bias) < .35f && fabsf(s.accel_g - 1) < .12f;
    for (unsigned i = 0; i < 4; ++i)
        stationary_gap = stationary_gap && s.counts[i] == 0;
    if (ms > 25 && stationary_gap)
        ms = 5;
    resample_after_save = false;
    healthy = s.valid && ms && ms <= 25 && isfinite(s.clockwise_dps) && isfinite(s.accel_g);
    voltage_ok = s.battery_v >= 9.6f && s.battery_v <= 13.2f;
    bool quiet = healthy && fabsf(s.clockwise_dps) < 3 && fabsf(s.accel_g - 1) < .12f;
    for (unsigned i = 0; i < 4; ++i)
        quiet = quiet && s.counts[i] == 0;
    if (quiet) {
        if (stationary_count < 1000)
            ++stationary_count;
        stationary_mean += (s.clockwise_dps - stationary_mean) / stationary_count;
        if (still_ms < 60000)
            still_ms += ms;
    } else {
        still_ms = stationary_count = 0;
        stationary_mean = 0;
    }
    if (healthy && calibrated)
        estimate(&chassis, &geometry, s.counts, s.clockwise_dps - bias, ms * .001f);
    if (!healthy)
        calibrated = false;
    if (!healthy || !voltage_ok) {
        bool active = playing();
        halt();
        if (active)
            player.phase = TRACK_FAULT;
        teaching = 0;
        servo_requested = false;
    }
    if (session && now - last_contact >= 3000)
        vehicle_link_lost();
    if (authorized && (int32_t)(now - deadline) >= 0) {
        halt();
        timed_out = true;
        requested_pan = chassis.pose.pan;
        requested_tilt = chassis.pose.tilt;
    }
    Twist v = manual;
    if (playing()) {
        v = player_tick(&player, &chassis, ms, store_count(), store_read, &requested_pan,
                        &requested_tilt);
        if (!playing())
            halt();
    }
    if (authorized) {
        float duty[4];
        wheel_control(&chassis, &geometry, v, ms * .001f, duty);
        board_drive(duty, deadline);
    }
    servo_overload_ms = servo_requested && s.gimbal_a > 2.5f ? servo_overload_ms + ms : 0;
    if (servo_overload_ms >= 500) {
        servo_ok = false;
        bool active = playing();
        halt();
        if (active)
            player.phase = TRACK_FAULT;
    }
    if (servo_requested && servo_ok) {
        float maxstep = 90 * ms * .001f;
        chassis.pose.pan += fmaxf(-maxstep, fminf(maxstep, requested_pan - chassis.pose.pan));
        chassis.pose.tilt += fmaxf(-maxstep, fminf(maxstep, requested_tilt - chassis.pose.tilt));
    }
    board_servo(&chassis.pose.pan, &chassis.pose.tilt, servo_requested && voltage_ok && servo_ok);
    if (session && (int32_t)(now - next_status) >= 0) {
        status();
        next_status = now + 50;
    }
}
