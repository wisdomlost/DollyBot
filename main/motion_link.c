#include "motion_link.h"
#include "esp_log.h"

void motion_link_init(void)
{
    ESP_LOGW("motion", "SIMULATION ONLY: STM32 disconnected; no motor output");
}

void motion_link_set(float linear, float angular)
{
    ESP_LOGI("motion", "SIM command linear=%.2f angular=%.2f", (double)linear, (double)angular);
}

bool motion_link_is_simulated(void) { return true; }
