#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

void stm32_uart_init(void);
void stm32_uart_discard_pending(void);

// Queues one validated DollyBot frame without waiting for the serial port.
void stm32_uart_forward(const uint8_t *data, size_t length, uint8_t type, bool new_session);
