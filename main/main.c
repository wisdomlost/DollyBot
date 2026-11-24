#include <stdint.h>
#include <string.h>

#include "dollybot_protocol.h"
#include "stm32_uart.h"

#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_err.h"
#include "esp_gap_bt_api.h"
#include "esp_log.h"
#include "esp_spp_api.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "nvs_flash.h"

static const char *const TAG = "dollybot_spp";
static const char device_name[] = "DollyBot-ESP32";
typedef enum {
    SPP_LINK_OPEN,
    SPP_LINK_CLOSED,
    SPP_RX,
    SPP_TX_DONE,
    SPP_CONGESTION,
} spp_event_kind_t;

typedef struct {
    spp_event_kind_t kind;
    uint32_t handle;
    int length;
    bool congested;
    uint8_t data[ESP_SPP_MAX_MTU];
} spp_event_t;

static QueueHandle_t spp_events;
// Only accessed by Bluedroid's SPP callback task.
static uint32_t callback_peer;

static bool enqueue_spp_event(spp_event_t *event)
{
    if (xQueueSend(spp_events, event, 0) == pdPASS) {
        return true;
    }

    ESP_LOGW(TAG, "SPP queue overflow, resetting connection");
    // Dropping part of a byte stream would corrupt the decoder's pending frame.
    xQueueReset(spp_events);
    event->kind = SPP_LINK_CLOSED;
    xQueueSend(spp_events, event, 0);
    if (callback_peer != 0) {
        ESP_ERROR_CHECK_WITHOUT_ABORT(esp_spp_disconnect(callback_peer));
    }
    return false;
}

static void disconnect_protocol_peer(uint32_t handle)
{
    stm32_uart_discard_pending();
    ESP_ERROR_CHECK_WITHOUT_ABORT(esp_spp_disconnect(handle));
}

static void protocol_task(void *arg)
{
    (void)arg;
    static dollybot_protocol_t protocol;
    uint32_t handle = 0;
    int bytes_in_flight = 0;
    bool congested = false;

    for (;;) {
        spp_event_t event;
        TickType_t wait = handle == 0 ? portMAX_DELAY : pdMS_TO_TICKS(20);
        BaseType_t have_event = xQueueReceive(spp_events, &event, wait);
        uint64_t now_ms = (uint64_t)esp_timer_get_time() / 1000;
        if (have_event == pdPASS) {
            switch (event.kind) {
            case SPP_LINK_OPEN:
                handle = event.handle;
                protocol = (dollybot_protocol_t){0};
                bytes_in_flight = 0;
                congested = false;
                break;
            case SPP_LINK_CLOSED:
                stm32_uart_discard_pending();
                handle = 0;
                break;
            case SPP_RX:
                if (handle != 0 && !dollybot_feed(&protocol, event.data, event.length, now_ms,
                                                  stm32_uart_forward)) {
                    ESP_LOGW(TAG, "DollyBot response queue overflow");
                    disconnect_protocol_peer(handle);
                    handle = 0;
                }
                break;
            case SPP_TX_DONE:
                if (handle != 0 && event.length != bytes_in_flight) {
                    ESP_LOGW(TAG, "SPP write failed");
                    disconnect_protocol_peer(handle);
                    handle = 0;
                }
                bytes_in_flight = 0;
                congested = event.congested;
                break;
            case SPP_CONGESTION:
                congested = event.congested;
                break;
            }
        }

        if (handle == 0) {
            continue;
        }

        uint8_t uart_data[128];
        int uart_length = stm32_uart_read(uart_data, sizeof(uart_data));
        if (uart_length > 0 && !dollybot_feed_stm32(&protocol, uart_data, (size_t)uart_length)) {
            ESP_LOGW(TAG, "DollyBot UART response queue overflow");
            disconnect_protocol_peer(handle);
            handle = 0;
            continue;
        }

        if (bytes_in_flight != 0 || congested) {
            continue;
        }
        // Preserve response order, with at most one SPP write in flight.
        uint8_t output[DOLLYBOT_STATUS_FRAME];
        int length = (int)dollybot_next_frame(&protocol, now_ms, output);
        if (length != 0) {
            if (ESP_ERROR_CHECK_WITHOUT_ABORT(esp_spp_write(handle, length, output)) == ESP_OK) {
                bytes_in_flight = length;
            } else {
                disconnect_protocol_peer(handle);
                handle = 0;
            }
        }
    }
}


static void gap_callback(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_BT_GAP_CFM_REQ_EVT:
        ESP_ERROR_CHECK_WITHOUT_ABORT(esp_bt_gap_ssp_confirm_reply(param->cfm_req.bda, true));
        break;
    case ESP_BT_GAP_AUTH_CMPL_EVT:
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) {
            ESP_LOGI(TAG, "Bluetooth authentication complete");
        } else {
            ESP_LOGW(TAG, "Bluetooth authentication failed (%d)", param->auth_cmpl.stat);
        }
        break;
    default:
        break;
    }
}

static void spp_callback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    static spp_event_t queued;
    switch (event) {
    case ESP_SPP_INIT_EVT:
        if (param->init.status == ESP_SPP_SUCCESS) {
            ESP_ERROR_CHECK_WITHOUT_ABORT(
                esp_spp_start_srv(ESP_SPP_SEC_AUTHENTICATE, ESP_SPP_ROLE_SLAVE, 0, "DollyBot SPP"));
        } else {
            ESP_LOGE(TAG, "SPP initialization failed (%d)", param->init.status);
        }
        break;
    case ESP_SPP_START_EVT:
        if (param->start.status != ESP_SPP_SUCCESS) {
            ESP_LOGE(TAG, "SPP server start failed (%d)", param->start.status);
        } else if (ESP_ERROR_CHECK_WITHOUT_ABORT(
                       esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE)) == ESP_OK) {
            ESP_LOGI(TAG, "SPP server ready as %s", device_name);
        }
        break;
    case ESP_SPP_SRV_OPEN_EVT:
        if (param->srv_open.status == ESP_SPP_SUCCESS) {
            if (callback_peer != 0) {
                ESP_ERROR_CHECK_WITHOUT_ABORT(esp_spp_disconnect(param->srv_open.handle));
                break;
            }
            callback_peer = param->srv_open.handle;
            queued.kind = SPP_LINK_OPEN;
            queued.handle = callback_peer;
            if (enqueue_spp_event(&queued)) {
                ESP_LOGI(TAG, "SPP client connected");
            }
        }
        break;
    case ESP_SPP_CLOSE_EVT:
        if (param->close.handle == callback_peer) {
            callback_peer = 0;
            queued.kind = SPP_LINK_CLOSED;
            enqueue_spp_event(&queued);
            ESP_LOGI(TAG, "SPP client disconnected");
        }
        break;
    case ESP_SPP_DATA_IND_EVT:
        if (callback_peer == 0 || param->data_ind.handle != callback_peer) {
            break;
        }
        queued.kind = SPP_RX;
        for (size_t offset = 0; offset < param->data_ind.len;) {
            size_t length = param->data_ind.len - offset;
            if (length > sizeof(queued.data)) {
                length = sizeof(queued.data);
            }
            queued.length = (int)length;
            memcpy(queued.data, param->data_ind.data + offset, length);
            if (!enqueue_spp_event(&queued)) {
                break;
            }
            offset += length;
        }
        break;
    case ESP_SPP_WRITE_EVT:
        if (callback_peer != 0 && param->write.handle == callback_peer) {
            queued.kind = SPP_TX_DONE;
            queued.length = param->write.status == ESP_SPP_SUCCESS ? param->write.len : -1;
            queued.congested = param->write.cong;
            enqueue_spp_event(&queued);
        }
        break;
    case ESP_SPP_CONG_EVT:
        if (callback_peer != 0 && param->cong.handle == callback_peer) {
            queued.kind = SPP_CONGESTION;
            queued.congested = param->cong.cong;
            enqueue_spp_event(&queued);
        }
        break;
    default:
        break;
    }
}

void app_main(void)
{
    ESP_ERROR_CHECK(nvs_flash_init());
    stm32_uart_init();

    spp_events = xQueueCreate(8, sizeof(spp_event_t));
    if (spp_events == NULL) {
        ESP_LOGE(TAG, "Could not allocate the SPP event queue");
        return;
    }
    if (xTaskCreate(protocol_task, "spp_protocol", 4096, NULL, 3, NULL) != pdPASS) {
        vQueueDelete(spp_events);
        ESP_LOGE(TAG, "Could not start the DollyBot protocol task");
        return;
    }


    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_BLE));
    esp_bt_controller_config_t controller = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&controller));
    ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT));

    esp_bluedroid_config_t bluedroid = BT_BLUEDROID_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bluedroid_init_with_cfg(&bluedroid));
    ESP_ERROR_CHECK(esp_bluedroid_enable());
    ESP_ERROR_CHECK(esp_bt_gap_register_callback(gap_callback));

    esp_bt_io_cap_t io_capability = ESP_BT_IO_CAP_NONE;
    ESP_ERROR_CHECK(esp_bt_gap_set_security_param(ESP_BT_SP_IOCAP_MODE,
                                                &io_capability, sizeof(io_capability)));
    ESP_ERROR_CHECK(esp_bt_gap_set_device_name(device_name));

    ESP_ERROR_CHECK(esp_spp_register_callback(spp_callback));
    const esp_spp_cfg_t spp = {
        .mode = ESP_SPP_MODE_CB,
        .enable_l2cap_ertm = false,
    };
    ESP_ERROR_CHECK(esp_spp_enhanced_init(&spp));
}
