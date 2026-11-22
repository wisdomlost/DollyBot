idf_component_register(SRCS "main.c" "dollybot_protocol.c" "stm32_uart.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES bt esp_driver_uart esp_timer nvs_flash)
