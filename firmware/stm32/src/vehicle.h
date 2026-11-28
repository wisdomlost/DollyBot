#pragma once
#include "platform.h"
void vehicle_init(void);
void vehicle_frame(const uint8_t *frame);
void vehicle_tick(Sensors s, uint32_t elapsed_ms);
void vehicle_link_lost(void);
