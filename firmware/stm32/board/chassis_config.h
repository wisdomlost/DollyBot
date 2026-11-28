#pragma once
/* 单位为毫米/计数。直推距离除以四轮向前计数增量之和。0 表示待标定。 */
#define 惯导系数 0.0f
/* Wiring polarity, only change if a wheel counts backward on a forward push. */
#define ENCODER_POLARITIES                                                                         \
    { 1, 1, 1, 1 }
#define MOTOR_POLARITIES                                                                           \
    { 1, 1, 1, 1 }
#define GYRO_CLOCKWISE_SIGN (-1.0f)
#define PAN_LIMIT_DEG 60.0f
#define TILT_LIMIT_DEG 40.0f
#define PAN_US_PER_DEG 10.0f
#define TILT_US_PER_DEG 10.0f
#define PAN_CENTER_US 1500
#define TILT_CENTER_US 1500
