#pragma once
#include <stdbool.h>
#include <stdint.h>

/* Wheel order LF, RF, LR, RR. Millimetres, seconds, degrees clockwise. */
typedef struct {
    float x, y, yaw, pan, tilt;
} Pose;
typedef struct {
    float right, forward, turn;
} Twist;
typedef struct {
    float odometry_scale; /* Millimetres per count of the signed four-wheel sum. */
    float max_wheel_speed, max_acceleration, kp, ki;
    int motor_sign[4];
} Geometry;
typedef struct {
    Pose pose;
    float wheel_speed[4], integral[4], setpoint[4];
    float yaw_rate, turn_integral, turn_output;
} Chassis;
typedef struct {
    uint32_t time, frame;
    Pose pose;
} Sample;
typedef enum { IDLE, RETURNING, ALIGNING, PLAYING, COMPLETE, TRACK_FAULT } Phase;
typedef struct {
    Phase phase;
    uint32_t index, elapsed, settled, delayed, stalled, loops;
    float hold_yaw, target_yaw;
} Player;
typedef Sample (*ReadSample)(uint32_t index);

float angle_delta(float target, float current);
bool geometry_valid(const Geometry *g);
void estimate(Chassis *c, const Geometry *g, const int16_t counts[4], float yaw_rate, float dt);
void wheel_control(Chassis *c, const Geometry *g, Twist v, float dt, float duty[4]);
void player_start(Player *p, unsigned loops, float yaw);
Twist player_tick(Player *p, const Chassis *c, uint32_t dt_ms, uint32_t count,
                  ReadSample read_sample, float *pan, float *tilt);
