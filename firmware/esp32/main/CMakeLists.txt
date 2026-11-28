idf_component_register(SRCS "main.c" "dollybot_protocol.c" "stm32_uart.c" "vofa.c" "vofa_wifi.c"
                       INCLUDE_DIRS "." "../../shared"
                       PRIV_REQUIRES bt esp_driver_uart nvs_flash esp_event esp_netif esp_wifi lwip)
