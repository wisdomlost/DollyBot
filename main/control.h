#pragma once
#include "protocol.h"

#define CONTROL_TIMEOUT_MS 500
typedef struct {
    float linear, angular;
    bool connected;
    const char *reason;
    uint32_t last_seq;
} control_state_t;
void control_init(void);
bool control_connect(int client);
void control_disconnect(int client);
bool control_apply(int client, const command_t *cmd);
void control_reject(int client);
void control_snapshot(control_state_t *out);
// Called by the guard task; also enables deterministic host verification.
void control_tick(void);
