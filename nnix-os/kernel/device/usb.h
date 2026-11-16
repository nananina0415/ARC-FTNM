#ifndef _DEVICE_USB_H
#define _DEVICE_USB_H

#include "types.h"
#include <stdint.h>

// USB HID 조이스틱 상태
typedef struct {
    int      axis_x;    // 좌우 이동 (-128 ~ 127)
    int      axis_y;    // 앞뒤 이동 (-128 ~ 127)
    uint32_t buttons;   // 버튼 비트마스크 (JOY_BTN_*)
} joystick_t;

// 아케이드 패널 버튼 비트. 큰 버튼 윗줄 w x y z, 아랫줄 a b c d, 작은 버튼 p m.
// 실제 단자를 이 비트로 옮기는 일은 FPGA USB 래퍼가 맡는다.
#define JOY_BTN_A (1u << 0)
#define JOY_BTN_B (1u << 1)
#define JOY_BTN_C (1u << 2)
#define JOY_BTN_D (1u << 3)
#define JOY_BTN_W (1u << 4)
#define JOY_BTN_X (1u << 5)
#define JOY_BTN_Y (1u << 6)
#define JOY_BTN_Z (1u << 7)
#define JOY_BTN_P (1u << 8)
#define JOY_BTN_M (1u << 9)

// USB HID 컨트롤러 초기화
extern void usb_init(void);

// 현재 조이스틱 상태를 out에 채움. 성공 0, 장치 없음 또는 미지원 -1
extern int usb_joystick(joystick_t* out);

#endif
