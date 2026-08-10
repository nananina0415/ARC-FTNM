#ifndef _DEVICE_TYPES_H
#define _DEVICE_TYPES_H

typedef enum {
    DEV_NONE = 0,
    DEV_BIOS_ROM,   // BRAM 기반 BIOS
    DEV_UART,       // USB-UART 브리지
    DEV_SPI,        // SPI 컨트롤러 (MicroSD)
    DEV_QSPI,       // QSPI Flash (256Mb)
    DEV_I2C,        // I2C 컨트롤러 (EEPROM 64Kbit)
    DEV_ETH,        // 기가비트 이더넷
    DEV_HDMI,       // HDMI 출력 (640×480)
    DEV_LCD,        // 40P FPC LCD
    DEV_GPIO,       // GPIO (LED x6, KEY x3, DIP스위치 x2)
    DEV_SEG7,       // 4자리 7세그먼트 x2
    DEV_TIMER,      // 하드웨어 타이머
    DEV_AUDIO,      // PCM 오디오 출력 (MMIO/DMA)
    DEV_USB,        // USB HID 컨트롤러 (조이스틱)
} dev_id_t;

typedef struct {
    unsigned int block;  // MMIO 블록 번호 (2^48 기준, 4KB 단위)
    unsigned int count;  // 블록 수 (0이면 끝 sentinel)
    dev_id_t     dev;
} mmio_entry_t;

// 보드 드라이버 라이브러리가 정의, DEV_NONE sentinel로 종료
extern const mmio_entry_t BOARD_MEMMAP[];

static inline unsigned long mmio_base(unsigned int block) {
    return (1UL << 48) | ((unsigned long)block << 12);
}

// 처음 발견되는 장치의 MMIO 엔트리를 반환, 없으면 NULL
static inline const mmio_entry_t* mmio_find(dev_id_t dev) {
    for (int i = 0; BOARD_MEMMAP[i].dev != DEV_NONE; i++)
        if (BOARD_MEMMAP[i].dev == dev) return &BOARD_MEMMAP[i];
    return (void*)0;
}

#endif
