#include "../../nnix-os/src/device/types.h"
#include "../../nnix-os/src/device/hdmi.h"
#include "../../nnix-os/src/device/lcd.h"
#include <stdint.h>

#ifndef MMIX_SIM

/* PA100T-EDU HDMI 레지스터 레이아웃 (DEV_HDMI MMIO 블록) */
typedef volatile struct {
    uint64_t width;
    uint64_t height;
    uint64_t dma_addr;
    uint64_t dma_stride;
    uint64_t ctrl;      /* bit0: 프레임 전송 트리거 */
    uint64_t status;    /* bit0: vsync 완료 */
} hdmi_regs_t;

static hdmi_regs_t* _hdmi = 0;

void hdmi_init(void) {
    const mmio_entry_t* e = mmio_find(DEV_HDMI);
    if (!e) return;
    _hdmi = (hdmi_regs_t*)mmio_base(e->block);
    _hdmi->width      = HDMI_WIDTH;
    _hdmi->height     = HDMI_HEIGHT;
    _hdmi->dma_stride = HDMI_WIDTH * 3;
}

int hdmi_present(const uint8_t* buf, int width, int height) {
    if (!_hdmi) return -1;
    _hdmi->width    = (uint64_t)width;
    _hdmi->height   = (uint64_t)height;
    _hdmi->dma_addr = (uint64_t)(uintptr_t)buf;
    _hdmi->ctrl     = 1;
    return 0;
}

#else /* MMIX_SIM: 시뮬레이터에서 PPM 파일로 출력 */

#include <stdio.h>

void hdmi_init(void) {}

int hdmi_present(const uint8_t* buf, int width, int height) {
    FILE* f = fopen("frame.ppm", "w");
    if (!f) return -1;
    fprintf(f, "P6\n%d %d\n255\n", width, height);
    fwrite(buf, 1, (size_t)(width * height * 3), f);
    fclose(f);
    return 0;
}

#endif /* MMIX_SIM */

/* LCD 미지원 — trap dispatch가 대안을 찾거나 -1 반환 */
void lcd_init(void) {}

int lcd_present(const uint8_t* buf, int width, int height) {
    (void)buf; (void)width; (void)height;
    return -1;
}
