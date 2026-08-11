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

// 현재 조이스틱 상태를 out에 채움. 성공 0, 장치 없음 또는 미지원 -1
extern int usb_joystick(joystick_t* out);

#endif
