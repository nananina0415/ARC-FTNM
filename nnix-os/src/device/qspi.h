#ifndef _DEVICE_QSPI_H
#define _DEVICE_QSPI_H

#include "types.h"
#include <stdint.h>

extern void qspi_init(void);
extern void qspi_read(uint32_t addr, void* buf, int len);
extern void qspi_write(uint32_t addr, const void* buf, int len);

#endif
