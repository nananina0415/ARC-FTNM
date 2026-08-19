// FPGA 전용 — trap.c의 TRAP 핸들러에서 호출
#include "fs.h"
#include "../libc/stdio.h"

MMIX_FILE file_table[MAX_FILES];

// 모드 문자열 → FatFs 오픈 플래그 변환
static BYTE mode_flags(const char* mode) {
    int plus = 0;
    const char* p = mode;
    while (*p) { if (*p++ == '+') { plus = 1; break; } }
    switch (mode[0]) {
    case 'r': return plus ? FA_READ | FA_WRITE | FA_OPEN_EXISTING
                          : FA_READ | FA_OPEN_EXISTING;
    case 'w': return plus ? FA_READ | FA_WRITE | FA_CREATE_ALWAYS
                          : FA_WRITE | FA_CREATE_ALWAYS;
    case 'a': return plus ? FA_READ | FA_WRITE | FA_OPEN_ALWAYS
                          : FA_WRITE | FA_OPEN_ALWAYS;
    }
    return FA_READ | FA_OPEN_EXISTING;
}

int file_open(int handle, const char* path, const char* mode) {
    if (handle < FD_STDERR + 1 || handle >= MAX_FILES) return -1;
    if (file_table[handle].used) return -1;
    FRESULT r = f_open(&file_table[handle].fil, path, mode_flags(mode));
    if (r != FR_OK) return -1;
    file_table[handle].used = 1;
    return 0;
}

int file_close(int handle) {
    if (handle < 0 || handle >= MAX_FILES || !file_table[handle].used) return -1;
    FRESULT r = f_close(&file_table[handle].fil);
    file_table[handle].used = 0;
    return (r == FR_OK) ? 0 : -1;
}

long file_read(int handle, void* buf, long size, long count) {
    if (handle < 0 || handle >= MAX_FILES || !file_table[handle].used) return -1;
    UINT br = 0;
    FRESULT r = f_read(&file_table[handle].fil, buf, (UINT)(size * count), &br);
    if (r != FR_OK) return -1;
    return (size > 0) ? (long)(br / (UINT)size) : 0;
}

long file_write(int handle, const void* buf, long size, long count) {
    if (handle < 0 || handle >= MAX_FILES || !file_table[handle].used) return -1;
    UINT bw = 0;
    FRESULT r = f_write(&file_table[handle].fil, buf, (UINT)(size * count), &bw);
    if (r != FR_OK) return -1;
    return (size > 0) ? (long)(bw / (UINT)size) : 0;
}

int file_seek(int handle, long offset, int whence) {
    if (handle < 0 || handle >= MAX_FILES || !file_table[handle].used) return -1;
    FIL* fp = &file_table[handle].fil;
    FSIZE_t pos;
    switch (whence) {
        case SEEK_SET: pos = (FSIZE_t)offset;                break;
        case SEEK_CUR: pos = f_tell(fp) + (FSIZE_t)offset;   break;
        case SEEK_END: pos = f_size(fp) + (FSIZE_t)offset;   break;
    default: return -1;
    }
    return (f_lseek(fp, pos) == FR_OK) ? 0 : -1;
}

long file_tell(int handle) {
    if (handle < 0 || handle >= MAX_FILES || !file_table[handle].used) return -1;
    return (long)f_tell(&file_table[handle].fil);
}
