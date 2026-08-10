#ifndef _DEVICE_LCD_H
#define _DEVICE_LCD_H

#include "types.h"
#include <stdint.h>

extern void lcd_init(void);
extern void lcd_present(const uint8_t* buf, int width, int height);

#endif
