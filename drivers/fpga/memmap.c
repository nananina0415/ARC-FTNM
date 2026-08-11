#include "../../nnix-os/src/device/types.h"

// FPGA 내부 장치 MMIO 블록 (상위 주소에서 할당, 보드 MMIO와 충돌 없음)
#define FPGA_BLOCK_OPL       0xFFFF
#define FPGA_BLOCK_AUDIO_MIX 0xFFFE

const mmio_entry_t CHIP_MEMMAP[] = {
    { FPGA_BLOCK_OPL,       1, DEV_OPL   },  // OPL3 FM 합성기
    { FPGA_BLOCK_AUDIO_MIX, 1, DEV_AUDIO },  // 오디오 믹서 + SFX DMA
    { 0, 0, DEV_NONE },
};
