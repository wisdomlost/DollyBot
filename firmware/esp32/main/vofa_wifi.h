#pragma once

#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"
#include "tuning_wire.h"

esp_err_t vofa_wifi_start(void);
void vofa_wifi_publish(const uint8_t *payload, size_t length);
uint8_t vofa_wifi_mode(void);
