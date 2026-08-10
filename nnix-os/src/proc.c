#include "proc.h"
#include "device/types.h"
#include "../third_party/fatfs/ff.h"

long proc_execve(const char* path) {
    FIL fil;
    if (f_open(&fil, path, FA_READ) != FR_OK) return -1;
    long clust = (long)fil.obj.sclust;
    f_close(&fil);

    const mmio_entry_t* e = mmio_find(DEV_BIOS_ROM);
    if (!e) return -1;
    long bios = (long)mmio_base(e->block);

    // $255 = 앱 start cluster, GO → BIOS (DDR3 클리어 후 앱 로드)
    register long r255 __asm__("$255") = clust;
    __asm__ volatile(
        "GO $0, %0, 0"
        :
        : "r"(bios), "r"(r255)
        : "$0"
    );
    return -1; // unreachable
}
