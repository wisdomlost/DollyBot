#include "control.h"
#include "motion_link.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static SemaphoreHandle_t lock;
static int owner = -1;
static int64_t last_drive;
static control_state_t state = {.reason = "boot"};

static void stop(const char *reason)
{
    if (state.linear != 0 || state.angular != 0) motion_link_set(0, 0);
    state.linear = state.angular = 0;
    state.reason = reason;
}

static void watchdog(void *arg)
{
    (void)arg;
    for (;;) {
        control_tick();
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

void control_tick(void)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (esp_timer_get_time() - last_drive >= CONTROL_TIMEOUT_MS * 1000LL &&
        (state.linear != 0 || state.angular != 0)) stop("timeout");
    xSemaphoreGive(lock);
}

void control_init(void)
{
    lock = xSemaphoreCreateMutex();
    configASSERT(lock);
    motion_link_init();
    motion_link_set(0, 0);
    configASSERT(xTaskCreate(watchdog, "control_guard", 3072, NULL, 6, NULL) == pdPASS);
}

bool control_connect(int client)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    bool ok = owner == -1;
    if (ok) {
        owner = client;
        state.connected = true;
        state.last_seq = 0;
        stop("connected");
    }
    xSemaphoreGive(lock);
    return ok;
}

void control_disconnect(int client)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (client == owner) {
        stop("disconnected");
        owner = -1;
        state.connected = false;
    }
    xSemaphoreGive(lock);
}

void control_reject(int client)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (client == owner) stop("invalid_command");
    xSemaphoreGive(lock);
}

bool control_apply(int client, const command_t *cmd)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    bool ok = client == owner && cmd->seq > state.last_seq;
    if (ok) {
        state.last_seq = cmd->seq;
        if (cmd->type == CMD_DRIVE) {
            state.linear = cmd->linear;
            state.angular = cmd->angular;
            state.reason = "drive";
            last_drive = esp_timer_get_time();
            motion_link_set(state.linear, state.angular);
        } else if (cmd->type == CMD_STOP) stop("stop");
        // Ping must never refresh the motion deadline.
    } else if (client == owner) stop("stale_sequence");
    xSemaphoreGive(lock);
    return ok;
}

void control_snapshot(control_state_t *out)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    *out = state;
    xSemaphoreGive(lock);
}
