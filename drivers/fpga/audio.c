#include "../../nnix-os/src/device/types.h"
#include "../../nnix-os/src/device/audio.h"
#include "../../nnix-os/src/device/opl.h"
#include <stdint.h>

/* OPL3 레지스터 레이아웃 (FPGA_BLOCK_OPL) */
typedef volatile struct {
    uint64_t addr;    /* 9비트 레지스터 주소 (bit8=보조 뱅크) */
    uint64_t data;    /* 8비트 데이터 */
    uint64_t status;  /* bit0: 쓰기 진행 중 (busy) */
} opl_regs_t;

/* 오디오 믹서 + SFX DMA 레지스터 레이아웃 (FPGA_BLOCK_AUDIO_MIX) */
typedef volatile struct {
    uint64_t sfx_buf_ptr;     /* SFX PCM 버퍼 DDR 주소 (0=무음) */
    uint64_t sfx_buf_len;     /* 버퍼 바이트 수 */
    uint64_t sfx_sample_rate; /* 샘플레이트 (11025) */
    uint64_t ctrl;            /* bit0: DMA 전송 시작 */
} audio_mix_regs_t;

static opl_regs_t*       _opl = 0;
static audio_mix_regs_t* _mix = 0;

void opl_init(void) {
    const mmio_entry_t* e = mmio_find(DEV_OPL);
    if (!e) return;
    _opl = (opl_regs_t*)mmio_base(e->block);
}

int opl_write(unsigned int reg, unsigned int data) {
    if (!_opl) opl_init();
    if (!_opl) return -1;
    _opl->addr = reg;
    while (_opl->status & 1);   /* 주소 래치 완료 대기 */
    _opl->data = data;
    while (_opl->status & 1);   /* 데이터 쓰기 완료 대기 */
    return 0;
}

void audio_init(void) {
    const mmio_entry_t* e = mmio_find(DEV_AUDIO);
    if (!e) return;
    _mix = (audio_mix_regs_t*)mmio_base(e->block);
    _mix->sfx_sample_rate = AUDIO_SAMPLE_RATE;
}

int audio_write(const int16_t* buf, int len) {
    if (!_mix) audio_init();
    if (!_mix) return -1;
    _mix->sfx_buf_ptr = (uint64_t)(uintptr_t)buf;  /* 0이면 믹서가 무음 처리 */
    _mix->sfx_buf_len = (uint64_t)len;
    _mix->ctrl        = 1;
    return 0;
}
