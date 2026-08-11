#include "../third_party/fatfs/ff.h"
#include "proc.h"

int main(void)
{
    static FATFS fs;
    f_mount(&fs, "", 1);   /* 드라이브 0 마운트 (disk_initialize → sd_card_init) */
    proc_execve("doom.mmix");
    for (;;);
}
