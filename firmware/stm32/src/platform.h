#pragma once
#include "motion.h"
extern const Geometry geometry;
typedef struct {
    int16_t counts[4];
    int64_t encoder_total[4];
    float clockwise_dps, accel_g, battery_v, motor_a, gimbal_a;
    bool valid, power_valid;
} Sensors;
void board_init(void);
uint32_t board_now(void);
uint32_t board_frame_id(void);
Sensors board_sample(void);
void board_drive(const float duty[4], uint32_t deadline);
void board_stop(void);
void board_servo(float *pan, float *tilt, bool enable);
void board_send(const uint8_t *data, unsigned length);
void board_send_tuning(const uint8_t *data, unsigned length);
int board_receive(void);
bool board_rx_lost(void);
