idf_component_register(SRCS "app_main.c" "wifi_ap.c" "web_server.c" "control.c" "protocol.c" "motion_link.c"
    INCLUDE_DIRS "."
    REQUIRES esp_wifi esp_event esp_netif nvs_flash esp_http_server esp_timer json lwip
    EMBED_FILES "../web/index.html" "../web/style.css" "../web/app.js")
