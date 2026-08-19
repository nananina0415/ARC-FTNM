#include "../../nnix-os/src/device/types.h"
#include "../../nnix-os/src/device/usb.h"
#include <stdint.h>

/*
 * FPGA USB HID 호스트 래퍼 MMIO 레이아웃 (블록 35, 각 필드 8바이트)
 *
 * m1nl/usb_hid_host RTL 출력 신호를 래퍼가 다음 레지스터로 노출:
 *
 *   offset 0x00  STATUS   [1:0]=typ (0=없음 3=게임패드)  [2]=connerr  [3]=busy
 *   offset 0x08  AXES     [0]=game_l  [1]=game_r  [8]=game_u  [9]=game_d
 *   offset 0x10  BUTTONS  [0]=A [1]=B [2]=X [3]=Y [4]=sel [5]=sta [9:6]=extra
 *
 * J11 IIC 헤더 핀 연결:
 *   IO_L16P_16 → usb_dp (D+)
 *   IO_L16N_16 → usb_dm (D-)
 */

typedef volatile struct {
    uint64_t status;
    uint64_t axes;
    uint64_t buttons;
} usb_hid_regs_t;

static usb_hid_regs_t* _usb = 0;

void usb_init(void) {
    const mmio_entry_t* e = mmio_find(DEV_USB);
    if (!e) return;
    _usb = (usb_hid_regs_t*)mmio_base(e->block);
}

int usb_joystick(joystick_t* out) {
    if (!_usb) usb_init();
    if (!_usb) return -1;

    if ((_usb->status & 3) != 3) return -1;  /* 장치 타입이 게임패드가 아님 */

    uint64_t ax = _usb->axes;
    uint64_t bt = _usb->buttons;

    out->axis_x  = (ax & 1) ? -128 : (ax & 2) ? 127 : 0;
    out->axis_y  = (ax & 0x100) ? -128 : (ax & 0x200) ? 127 : 0;
    out->buttons = (uint32_t)bt;
    return 0;
}
