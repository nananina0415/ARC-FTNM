#ifndef _DEVICE_SEG7_H
#define _DEVICE_SEG7_H

#include "types.h"
#include <stdint.h>

extern void seg7_init(void);
extern void seg7_write(int unit, uint8_t segments);  // unit: 0~1 (4자리 x2)
extern void seg7_write_hex(int unit, uint8_t val);   // 16진수 2자리 표시

#endif
