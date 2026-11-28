#include "motion.h"
#include <math.h>
#include <string.h>

#define RAD 0.01745329252f
static float limit(float v, float m) { return fmaxf(-m, fminf(v, m)); }
float angle_delta(float target, float current) {
    float d = fmodf(target - current + 180.0f, 360.0f);
    return (d < 0 ? d + 360.0f : d) - 180.0f;
}
bool geometry_valid(const Geometry *g) {
    if (!(isfinite(g->odometry_scale) && g->odometry_scale > 0 && g->max_wheel_speed > 0 &&
          g->max_acceleration > 0 && g->kp > 0 && g->ki >= 0))
        return false;
    for (unsigned i = 0; i < 4; ++i)
        if (g->motor_sign[i] != 1 && g->motor_sign[i] != -1)
            return false;
    return true;
}
void estimate(Chassis *c, const Geometry *g, const int16_t counts[4], float rate, float dt) {
    int32_t forward_counts = (int32_t)counts[0] + counts[1] + counts[2] + counts[3];
    int32_t right_counts = (int32_t)counts[0] - counts[1] - counts[2] + counts[3];
    float forward = forward_counts * g->odometry_scale;
    float right = right_counts * g->odometry_scale;

    /* Wheel-speed feedback uses the same scale, without the four-wheel average. */
    float wheel_velocity_scale = (4.0f * g->odometry_scale) / dt;
    for (unsigned i = 0; i < 4; ++i) {
        float speed = counts[i] * wheel_velocity_scale;
        c->wheel_speed[i] += 0.25f * (speed - c->wheel_speed[i]);
    }
    float mid = (c->pose.yaw + 0.5f * rate * dt) * RAD;
    c->pose.x += right * cosf(mid) + forward * sinf(mid);
    c->pose.y += forward * cosf(mid) - right * sinf(mid);
    c->pose.yaw = angle_delta(c->pose.yaw + rate * dt, 0);
    c->yaw_rate = rate;
}
void wheel_control(Chassis *c, const Geometry *g, Twist v, float dt, float duty[4]) {
    /* Control rotation from the gyro. The PI output is a differential wheel
     * speed, so wheelbase and track width are not calibration inputs. */
    float yaw_error = v.turn - c->yaw_rate;
    float turn_limit = g->max_wheel_speed * .65f;
    float turn_output = 1.8f * yaw_error + c->turn_integral;
    if (fabsf(turn_output) < turn_limit || turn_output * yaw_error < 0)
        c->turn_integral = limit(c->turn_integral + 5.0f * yaw_error * dt, turn_limit);
    float r = v.right, f = v.forward, t = limit(turn_output, turn_limit);
    float targets[4] = {f + r + t, f - r - t, f - r + t, f + r - t};
    float peak = g->max_wheel_speed;
    for (unsigned i = 0; i < 4; ++i)
        peak = fmaxf(peak, fabsf(targets[i]));
    for (unsigned i = 0; i < 4; ++i) {
        float target = targets[i] * g->max_wheel_speed / peak;
        c->setpoint[i] += limit(target - c->setpoint[i], g->max_acceleration * dt);
        float error = c->setpoint[i] - c->wheel_speed[i];
        float drive = c->setpoint[i] / g->max_wheel_speed + g->kp * error + c->integral[i];
        if (fabsf(drive) < 1 || drive * error < 0)
            c->integral[i] = limit(c->integral[i] + g->ki * error * dt, 0.5f);
        duty[i] = limit(drive, 1) * g->motor_sign[i];
    }
}
void player_start(Player *p, unsigned loops, float yaw) {
    *p = (Player){.phase = RETURNING, .loops = loops, .hold_yaw = yaw};
}
Twist player_tick(Player *p, const Chassis *c, uint32_t dt, uint32_t count, ReadSample read_sample,
                  float *pan, float *tilt) {
    Twist stop = {0};
    if (!count || p->phase < RETURNING || p->phase > PLAYING)
        return stop;
    Sample a = read_sample(p->index), b = a;
    float u = 0, seconds = 0;
    if (p->phase == PLAYING && p->index + 1 < count) {
        b = read_sample(p->index + 1);
        uint32_t interval = b.time - a.time;
        if (!interval) {
            p->phase = TRACK_FAULT;
            return stop;
        }
        seconds = interval * .001f;
        u = fminf(1, (float)p->elapsed / interval);
    }
    float dx = a.pose.x + u * (b.pose.x - a.pose.x) - c->pose.x;
    float dy = a.pose.y + u * (b.pose.y - a.pose.y) - c->pose.y;
    float yaw =
        p->phase == RETURNING ? p->hold_yaw : a.pose.yaw + u * angle_delta(b.pose.yaw, a.pose.yaw);
    float dyaw = angle_delta(yaw, c->pose.yaw);
    float dist = hypotf(dx, dy);
    bool close = dist < 12 && fabsf(dyaw) < 3;
    bool still = fabsf(c->yaw_rate) < 4;
    for (unsigned i = 0; i < 4; ++i)
        still = still && fabsf(c->wheel_speed[i]) < 18;
    p->settled = close && still ? p->settled + dt : 0;
    bool track = dist < 100 && fabsf(dyaw) < 18;
    /* A programmed dwell starts only after actual arrival and wheel settling. */
    if (seconds >= .15f && a.pose.x == b.pose.x && a.pose.y == b.pose.y && a.pose.yaw == b.pose.yaw)
        track = close && still;
    float vx = 2 * dx, vy = 2 * dy, omega = 2.5f * dyaw;
    *pan = a.pose.pan + u * (b.pose.pan - a.pose.pan);
    *tilt = a.pose.tilt + u * (b.pose.tilt - a.pose.tilt);
    if (p->phase == RETURNING && dist < 12) {
        p->phase = ALIGNING;
        p->settled = 0;
    } else if (p->phase == ALIGNING && p->settled >= 200) {
        p->phase = PLAYING;
        p->settled = p->delayed = 0;
    } else if (p->phase == PLAYING && seconds > 0 && track) {
        vx += (b.pose.x - a.pose.x) / seconds;
        vy += (b.pose.y - a.pose.y) / seconds;
        omega += angle_delta(b.pose.yaw, a.pose.yaw) / seconds;
        p->elapsed += dt;
        while (p->index + 1 < count) {
            uint32_t span = read_sample(p->index + 1).time - read_sample(p->index).time;
            if (p->elapsed < span)
                break;
            p->elapsed -= span;
            ++p->index;
        }
        p->delayed = 0;
    } else if (p->phase == PLAYING && !seconds && p->settled >= 200) {
        if (p->loops > 1)
            player_start(p, p->loops - 1, c->pose.yaw);
        else
            p->phase = COMPLETE;
        return stop;
    }
    if (p->phase == PLAYING && (!track || !seconds))
        p->delayed += dt;
    else if (p->phase != PLAYING)
        p->delayed += dt;
    p->stalled = still && (hypotf(vx, vy) > 50 || fabsf(omega) > 15) ? p->stalled + dt : 0;
    if (p->delayed > (p->phase == PLAYING ? 10000u : 90000u) || p->stalled > 3000) {
        p->phase = TRACK_FAULT;
        return stop;
    }
    float max_speed = p->phase == PLAYING ? 400 : 100;
    float scale = max_speed / fmaxf(max_speed, hypotf(vx, vy));
    float theta = c->pose.yaw * RAD;
    return (Twist){scale * (vx * cosf(theta) - vy * sinf(theta)),
                   scale * (vx * sinf(theta) + vy * cosf(theta)),
                   limit(omega, p->phase == PLAYING ? 150 : 45)};
}
