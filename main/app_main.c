#include "control.h"
#include "wifi_ap.h"
#include "web_server.h"
#include "nvs_flash.h"

void app_main(void)
{
    // Preserve existing NVS: report errors instead of silently erasing storage.
    ESP_ERROR_CHECK(nvs_flash_init());
    control_init();
    wifi_ap_start();
    web_server_start();
}
