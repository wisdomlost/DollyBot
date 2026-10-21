#pragma once
#include <stdbool.h>

// Normalized body velocity; replace this backend with a bounded UART transport.
void motion_link_init(void);
void motion_link_set(float linear, float angular);
bool motion_link_is_simulated(void);
