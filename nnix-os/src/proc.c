#include "proc.h"
#include "device/types.h"
#include "../third_party/fatfs/ff.h"

/* BIOS와 앱이 공유하는 전달 구조체.
 * BIOS: cluster를 읽어 바이너리 로드 후 $255 보존 채 앱으로 점프.
 * 앱 crt0.s: argc(@8), argv(@16)를 읽어 main()에 전달. */
typedef struct {
    long    cluster;   /* offset  0 — BIOS용 */
    int     argc;      /* offset  8 — 앱용    */
    /* padding 4 bytes */
    char**  argv;      /* offset 16 — 앱용    */
} _execve_t;

static _execve_t _args;

long proc_execve(const char* path, int argc, char** argv) {
    FIL fil;
    if (f_open(&fil, path, FA_READ) != FR_OK) return -1;
    _args.cluster = (long)fil.obj.sclust;
    _args.argc    = argc;
    _args.argv    = argv;
    f_close(&fil);

    const mmio_entry_t* e = mmio_find(DEV_BIOS_ROM);
    if (!e) return -1;
    long bios = (long)mmio_base(e->block);

    register long r255 __asm__("$255") = (long)&_args;
    __asm__ volatile(
        "GO $0, %0, 0"
        :
        : "r"(bios), "r"(r255)
        : "$0"
    );
    return -1; /* unreachable */
}
