#include "../../nnix-os/src/device.h"

// PA100T-EDU 보드 MMIO 맵 (2^48 기준 4KB 블록 단위)
const mmio_entry_t BOARD_MEMMAP[] = {
    {  0, 16, DEV_BIOS_ROM },  // 블록  0~15  : 64KB BRAM
    { 16,  1, DEV_UART     },  // 블록 16
    { 17,  1, DEV_SPI      },  // 블록 17     : MicroSD
    { 18,  1, DEV_QSPI     },  // 블록 18     : QSPI Flash 256Mb
    { 19,  1, DEV_I2C      },  // 블록 19     : E2PROM 64Kbit
    { 20,  4, DEV_ETH      },  // 블록 20~23  : 기가비트 이더넷
    { 24,  4, DEV_HDMI     },  // 블록 24~27  : HDMI 출력
    { 28,  4, DEV_LCD      },  // 블록 28~31  : 40P FPC LCD
    { 32,  1, DEV_GPIO     },  // 블록 32     : LED/KEY/DIP스위치
    { 33,  1, DEV_SEG7     },  // 블록 33     : 4자리 7세그먼트 x2
    { 34,  1, DEV_TIMER    },  // 블록 34
    {  0,  0, DEV_NONE     },
};
