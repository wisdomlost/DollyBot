#include "wifi_ap.h"
#include <string.h>
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "sdkconfig.h"

void wifi_ap_start(void)
{
    size_t n = strlen(CONFIG_DOLLY_AP_PASSWORD);
    ESP_ERROR_CHECK(n >= 8 && n <= 63 ? ESP_OK : ESP_ERR_INVALID_ARG);
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_t *ap = esp_netif_create_default_wifi_ap();
    assert(ap);
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    wifi_config_t config = {.ap = {
        .ssid = CONFIG_DOLLY_AP_SSID,
        .password = CONFIG_DOLLY_AP_PASSWORD,
        .channel = CONFIG_DOLLY_AP_CHANNEL,
        .max_connection = 4,
        .authmode = WIFI_AUTH_WPA2_PSK,
        .pmf_cfg = {.capable = true},
    }};
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &config));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI("wifi", "AP %s ready; open http://192.168.4.1", CONFIG_DOLLY_AP_SSID);
}
