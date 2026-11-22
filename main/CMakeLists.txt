idf_component_register(SRCS "main.c" "dollybot_protocol.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES bt esp_timer nvs_flash)
