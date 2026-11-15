#ifndef _DEVICE_GPIO_H
#define _DEVICE_GPIO_H

#include "types.h"
#include <stdint.h>

extern void     gpio_init(void);
extern void     gpio_led_set(int idx, int on);   // LED 0~5
extern int      gpio_key_get(int idx);            // KEY 0~2
extern uint8_t  gpio_dip_get(void);              // DIP스위치 비트마스크

#endif
