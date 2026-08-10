#ifndef _DEVICE_BIOS_ROM_H
#define _DEVICE_BIOS_ROM_H

#include "types.h"
#include <stdint.h>

extern void     bios_rom_init(void);
extern uint32_t bios_rom_read(uint32_t offset);

#endif
