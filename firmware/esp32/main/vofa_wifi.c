#include "vofa_wifi.h"

#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <string.h>

#include "esp_check.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "lwip/sockets.h"
#include "sdkconfig.h"
#include "vofa.h"

enum {
    WIFI_READY = BIT0,
    VOFA_PORT = 9000,
};
static const char *const TAG = "vofa_wifi";
static EventGroupHandle_t wifi_state;
static QueueHandle_t samples;
static struct sockaddr_in receiver;
static atomic_uint selected_mode;

typedef struct {
    uint8_t data[TUNE_MAX_PAYLOAD];
    size_t length;
} tune_sample_t;

uint8_t vofa_wifi_mode(void)
{
    return (uint8_t)atomic_load_explicit(&selected_mode, memory_order_relaxed);
}

void vofa_wifi_publish(const uint8_t *payload, size_t length)
{
    if (samples != NULL && payload[0] == vofa_wifi_mode()) {
        tune_sample_t sample = {.length = length};
        memcpy(sample.data, payload, length);
        xQueueOverwrite(samples, &sample);
    }
}

static void wifi_event(void *arg, esp_event_base_t base, int32_t event, void *data)
{
    (void)arg;
    (void)data;
    if (base == IP_EVENT && event == IP_EVENT_STA_GOT_IP) {
        xEventGroupSetBits(wifi_state, WIFI_READY);
    } else if (base == WIFI_EVENT && (event == WIFI_EVENT_STA_START || event == WIFI_EVENT_STA_DISCONNECTED)) {
        xEventGroupClearBits(wifi_state, WIFI_READY);
        esp_wifi_connect();
    }
}

static int connect_receiver(void)
{
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) {
        return -1;
    }
    if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0) {
        close(fd);
        return -1;
    }
    if (connect(fd, (const struct sockaddr *)&receiver, sizeof(receiver)) < 0) {
        if (errno != EINPROGRESS) {
            close(fd);
            return -1;
        }
        fd_set writable;
        FD_ZERO(&writable);
        FD_SET(fd, &writable);
        struct timeval timeout = {.tv_usec = 500000};
        int error = 0;
        socklen_t size = sizeof(error);
        if (select(fd + 1, NULL, &writable, NULL, &timeout) != 1 ||
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0 || error != 0) {
            close(fd);
            return -1;
        }
    }
    int enabled = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled));
    return fd;
}

static bool try_send(int fd, const char *line, size_t length)
{
    ssize_t sent = send(fd, line, length, 0);
    // Drop a whole sample on backpressure. End the connection on a partial line.
    return sent == (ssize_t)length || (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
}

static void stream_samples(int fd)
{
    tune_sample_t sample;
    vofa_command_t command = {0};
    char line[VOFA_LINE_CAPACITY];
    // The first sample on each connection must have been captured after connecting.
    xQueueReset(samples);
    while (xEventGroupGetBits(wifi_state) & WIFI_READY) {
        BaseType_t available = xQueueReceive(samples, &sample, pdMS_TO_TICKS(100));
        uint8_t bytes[64];
        ssize_t received = recv(fd, bytes, sizeof(bytes), 0);
        if (received == 0 || (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
            break;
        }
        for (ssize_t i = 0; i < received; ++i) {
            uint8_t mode;
            if (vofa_command_byte(&command, bytes[i], &mode) && mode != vofa_wifi_mode()) {
                atomic_store_explicit(&selected_mode, mode, memory_order_relaxed);
                xQueueReset(samples);
                available = pdFAIL;
            }
        }
        if (available == pdPASS && sample.data[0] == vofa_wifi_mode()) {
            size_t length = vofa_format(line, sizeof(line), sample.data, sample.length);
            if (length != 0 && !try_send(fd, line, length)) {
                break;
            }
        }
    }
}

static void vofa_wifi_task(void *arg)
{
    (void)arg;
    for (;;) {
        xEventGroupWaitBits(wifi_state, WIFI_READY, pdFALSE, pdTRUE, portMAX_DELAY);
        int fd = connect_receiver();
        if (fd >= 0) {
            stream_samples(fd);
            atomic_store_explicit(&selected_mode, TUNE_OFF, memory_order_relaxed);
            close(fd);
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

esp_err_t vofa_wifi_start(void)
{
    const char *const host = CONFIG_DOLLY_VOFA_HOST_IPV4;
    if (host[0] == '\0') {
        ESP_LOGI(TAG, "Set the VOFA+ host IPv4 in menuconfig to enable telemetry");
        return ESP_OK;
    }
    const char *const ssid = CONFIG_DOLLY_VOFA_WIFI_SSID;
    const char *const password = CONFIG_DOLLY_VOFA_WIFI_PASSWORD;
    size_t ssid_len = strlen(ssid), password_len = strlen(password);
    ESP_RETURN_ON_FALSE(ssid_len && ssid_len <= 32 && password_len >= 8 && password_len <= 63,
                        ESP_ERR_INVALID_ARG, TAG, "Configure a 2.4 GHz WPA2 network");
    receiver.sin_family = AF_INET;
    receiver.sin_port = htons(VOFA_PORT);
    ESP_RETURN_ON_FALSE(inet_pton(AF_INET, host, &receiver.sin_addr) == 1,
                        ESP_ERR_INVALID_ARG, TAG, "Invalid VOFA+ host IPv4");

    ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "netif");
    ESP_RETURN_ON_ERROR(esp_event_loop_create_default(), TAG, "event loop");
    ESP_RETURN_ON_FALSE(esp_netif_create_default_wifi_sta() != NULL, ESP_ERR_NO_MEM, TAG, "STA netif");
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&init), TAG, "Wi-Fi init");
    wifi_config_t station = {.sta = {.threshold.authmode = WIFI_AUTH_WPA2_PSK}};
    memcpy(station.sta.ssid, ssid, ssid_len);
    memcpy(station.sta.password, password, password_len);
    ESP_RETURN_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), TAG, "Wi-Fi storage");
    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_STA), TAG, "STA mode");
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_STA, &station), TAG, "STA config");

    wifi_state = xEventGroupCreate();
    ESP_RETURN_ON_FALSE(wifi_state != NULL, ESP_ERR_NO_MEM, TAG, "Wi-Fi state");
    ESP_RETURN_ON_ERROR(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event, NULL),
                        TAG, "Wi-Fi events");
    ESP_RETURN_ON_ERROR(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event, NULL),
                        TAG, "IP events");
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "Wi-Fi start");

    samples = xQueueCreate(1, sizeof(tune_sample_t));
    ESP_RETURN_ON_FALSE(samples != NULL, ESP_ERR_NO_MEM, TAG, "Telemetry queue");
    if (xTaskCreate(vofa_wifi_task, "vofa_wifi", 5120, NULL, 2, NULL) != pdPASS) {
        vQueueDelete(samples);
        samples = NULL;
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}
