#include "stm32_uart.h"

#include <stdatomic.h>
#include <string.h>

#include "dollybot_protocol.h"
#include "driver/uart.h"
#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

enum {
    STM32_TX_GPIO = 25,
    STM32_RX_GPIO = 26,
    STM32_BAUD = 115200,
    UART_RX_BUFFER_SIZE = 2048,
    TX_QUEUE_DEPTH = 8,
    MOTION_MAX_AGE_MS = 500,
};

typedef struct {
    uint8_t data[DOLLYBOT_MAX_FRAME];
    size_t length;
    uint8_t type;
    unsigned session_generation;
    unsigned motion_generation;
    TickType_t queued_at;
} uart_tx_frame_t;

static const char *const TAG = "stm32_uart";
static const uart_port_t port = UART_NUM_1;
static QueueHandle_t tx_queue;
static atomic_uint session_generation;
static atomic_uint motion_generation;

static void uart_tx_task(void *arg)
{
    (void)arg;
    uart_tx_frame_t frame;
    for (;;) {
        if (xQueueReceive(tx_queue, &frame, portMAX_DELAY) != pdPASS) {
            continue;
        }
        if (frame.session_generation != atomic_load(&session_generation)) {
            continue;
        }
        if ((frame.type == DOLLYBOT_MOTION || frame.type == 0x44 || frame.type == 0x45) &&
            (frame.motion_generation != atomic_load(&motion_generation) ||
             xTaskGetTickCount() - frame.queued_at >= pdMS_TO_TICKS(MOTION_MAX_AGE_MS))) {
            continue;
        }

        int written = uart_write_bytes(port, frame.data, frame.length);
        if (written != (int)frame.length) {
            ESP_LOGW(TAG, "UART TX write returned %d for %zu bytes", written, frame.length);
        }
    }
}

int stm32_uart_read(uint8_t *data, size_t capacity)
{
    return uart_read_bytes(port, data, capacity, 0);
}

void stm32_uart_init(void)
{
    const uart_config_t config = {
        .baud_rate = STM32_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    ESP_ERROR_CHECK(uart_param_config(port, &config));
    ESP_ERROR_CHECK(uart_set_pin(port, STM32_TX_GPIO, STM32_RX_GPIO,
                                 UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE));
    ESP_ERROR_CHECK(uart_driver_install(port, UART_RX_BUFFER_SIZE, 0, 0, NULL, 0));

    tx_queue = xQueueCreate(TX_QUEUE_DEPTH, sizeof(uart_tx_frame_t));
    ESP_ERROR_CHECK(tx_queue != NULL ? ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(xTaskCreate(uart_tx_task, "stm32_uart_tx", 3072, NULL, 3, NULL) == pdPASS
                        ? ESP_OK : ESP_ERR_NO_MEM);
    ESP_LOGI(TAG, "UART1 TX=GPIO%d RX=GPIO%d baud=%d 8N1", STM32_TX_GPIO, STM32_RX_GPIO, STM32_BAUD);
}

void stm32_uart_discard_pending(void)
{
    atomic_fetch_add(&session_generation, 1);
    xQueueReset(tx_queue);
    ESP_ERROR_CHECK_WITHOUT_ABORT(uart_flush_input(port));
}

void stm32_uart_forward(const uint8_t *data, size_t length, uint8_t type, bool new_session)
{
    // The Bluetooth protocol task is the only producer of UART frames.
    if (new_session) {
        stm32_uart_discard_pending();
    }
    if (type == DOLLYBOT_STOP) {
        atomic_fetch_add(&motion_generation, 1);
    } else if (uxQueueSpacesAvailable(tx_queue) <= 1) {
        ESP_LOGW(TAG, "UART TX queue busy, dropped frame type=0x%02X", (unsigned)type);
        return;
    }

    uart_tx_frame_t frame = {
        .length = length,
        .type = type,
        .session_generation = atomic_load(&session_generation),
        .motion_generation = atomic_load(&motion_generation),
        .queued_at = xTaskGetTickCount(),
    };
    memcpy(frame.data, data, length);
    BaseType_t sent = (type == DOLLYBOT_STOP || type == 0x7e)
        ? xQueueSendToFront(tx_queue, &frame, 0) : xQueueSend(tx_queue, &frame, 0);
    if (sent != pdPASS) {
        ESP_LOGW(TAG, "UART TX queue full, dropped frame type=0x%02X", (unsigned)type);
    }
}
