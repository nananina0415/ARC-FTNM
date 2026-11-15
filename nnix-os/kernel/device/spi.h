#ifndef _DEVICE_SPI_H
#define _DEVICE_SPI_H

#include "types.h"
#include <stdint.h>

extern void    spi_init(void);
extern uint8_t spi_transfer(uint8_t byte);

#endif
