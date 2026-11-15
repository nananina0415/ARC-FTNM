#ifndef _DEVICE_I2C_H
#define _DEVICE_I2C_H

#include "types.h"
#include <stdint.h>

extern void i2c_init(void);
extern int  i2c_write(uint8_t addr, const uint8_t* buf, int len);
extern int  i2c_read(uint8_t addr, uint8_t* buf, int len);

#endif
