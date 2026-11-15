#ifndef _DEVICE_LCD_H
#define _DEVICE_LCD_H

#include "types.h"
#include <stdint.h>

/* TODO: 보드에 장착된 40P FPC LCD 실제 해상도로 수정 */
#define LCD_WIDTH  480
#define LCD_HEIGHT 272

extern void lcd_init(void);
// 성공 0, 미지원 -1
extern int lcd_present(const uint8_t* buf, int width, int height);

#endif
