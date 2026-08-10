#ifndef _DEVICE_USB_H
#define _DEVICE_USB_H

#include "types.h"
#include <stdint.h>

// USB HID 조이스틱 상태
typedef struct {
    int      axis_x;    // 좌우 이동 (-128 ~ 127)
    int      axis_y;    // 앞뒤 이동 (-128 ~ 127)
    uint32_t buttons;   // 버튼 비트마스크 (개수 미정)
} joystick_t;

// USB HID 컨트롤러 초기화
extern void usb_init(void);

// USB HID 패킷 처리 (메인 루프 또는 인터럽트에서 호출)
extern void usb_poll(void);

// 현재 조이스틱 상태 반환
extern joystick_t usb_joystick(void);

#endif
