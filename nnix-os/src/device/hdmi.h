#ifndef _DEVICE_HDMI_H
#define _DEVICE_HDMI_H

#include "types.h"
#include <stdint.h>

// 해상도
#define HDMI_WIDTH  640
#define HDMI_HEIGHT 480

// HDMI 초기화 (MMIO/DMA 버퍼 설정 포함)
extern void hdmi_init(void);

// RGB 프레임버퍼를 화면에 출력
// buf: RGB888, width×height 픽셀
extern void hdmi_present(const uint8_t* buf, int width, int height);

#endif
