#include "proc.h"
#include "device/types.h"

long proc_execve(long exe_cluster, long link_cluster) {
    const mmio_entry_t* e = mmio_find(DEV_BIOS_ROM);
    if (!e) return -1;
    long bios = (long)mmio_base(e->block);

    register long r255 __asm__("$255") = exe_cluster;
    register long r254 __asm__("$254") = link_cluster;
    __asm__ volatile(
        "GO $0, %0, 0"
        :
        : "r"(bios), "r"(r255), "r"(r254)
        : "$0"
    );
    return -1; /* unreachable */
}
