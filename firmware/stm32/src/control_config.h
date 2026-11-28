#pragma once

/* Four wheel speed loops, reference and feedback in mm/s. */
#define WHEEL_SPEED_KP 0.0018f
#define WHEEL_SPEED_KI 0.002f

/* Angular-rate PI, clockwise deg/s error to differential wheel speed. */
#define YAW_RATE_KP 1.8f
#define YAW_RATE_KI 5.0f

/* Replay heading P, angle error in degrees to angular rate in deg/s. */
#define REPLAY_YAW_KP 2.5f
