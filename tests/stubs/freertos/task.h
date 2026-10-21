#pragma once
#include <stddef.h>
static inline int xTaskCreate(void (*task)(void *), const char *name, int stack, void *arg, int priority, void *handle)
{ (void)task; (void)name; (void)stack; (void)arg; (void)priority; (void)handle; return 1; }
static inline void vTaskDelay(int ticks) { (void)ticks; }
