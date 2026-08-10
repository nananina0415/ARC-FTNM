#include "syscall.h"
#include "../libc/stdio.h"
#include "device/types.h"
#include "proc.h"

// 커널 스택 (trap.s가 $254로 가리킴)
static long _kstack[512];
long* const _kstack_top = _kstack + 512;

// FatFs 파일 핸들 테이블 (handle 0,1,2 = stdin/stdout/stderr → UART)
#include "../third_party/fatfs/ff.h"
static FIL _fil[16];
static int _fil_used[16];

// UART 출력 (DEV_UART MMIO 레지스터 직접 접근)
static void _uart_write(const char* buf, long len) {
    const mmio_entry_t* e = mmio_find(DEV_UART);
    if (!e) return;
    volatile long* tx = (volatile long*)mmio_base(e->block);
    for (long i = 0; i < len; i++)
        tx[0] = (long)(unsigned char)buf[i];
}

static long _trap_fopen(long opt, const char* path) {
    int i;
    for (i = 3; i < 16; i++)
        if (!_fil_used[i]) break;
    if (i == 16) return -1;
    BYTE mode;
    switch (opt) {
    case 0: mode = FA_READ;                        break; // "r"
    case 1: mode = FA_WRITE | FA_CREATE_ALWAYS;    break; // "w"
    case 2: mode = FA_WRITE | FA_OPEN_APPEND;      break; // "a"
    case 3: mode = FA_READ | FA_WRITE;             break; // "r+"
    case 4: mode = FA_READ | FA_WRITE | FA_CREATE_ALWAYS; break; // "w+"
    case 5: mode = FA_READ | FA_WRITE | FA_OPEN_APPEND;   break; // "a+"
    default: return -1;
    }
    if (f_open(&_fil[i], path, mode) != FR_OK) return -1;
    _fil_used[i] = 1;
    return i;
}

static long _trap_fclose(int h) {
    if (h < 3 || h >= 16 || !_fil_used[h]) return -1;
    FRESULT r = f_close(&_fil[h]);
    _fil_used[h] = 0;
    return (r == FR_OK) ? 0 : -1;
}

static long _trap_fread(struct { long buf; long total; long handle; }* a) {
    int h = (int)a->handle;
    if (h < 3 || h >= 16 || !_fil_used[h]) return -1;
    UINT nr;
    if (f_read(&_fil[h], (void*)a->buf, (UINT)a->total, &nr) != FR_OK) return -1;
    return (long)nr;
}

static long _trap_fwrite(struct { long buf; long total; long handle; }* a) {
    int h = (int)a->handle;
    if (h == 1 || h == 2) {
        // stdout / stderr → UART
        _uart_write((const char*)a->buf, a->total);
        return a->total;
    }
    if (h < 3 || h >= 16 || !_fil_used[h]) return -1;
    UINT nw;
    if (f_write(&_fil[h], (const void*)a->buf, (UINT)a->total, &nw) != FR_OK) return -1;
    return (long)nw;
}

static long _trap_fseek(struct { long handle; long offset; }* a, int whence) {
    int h = (int)a->handle;
    if (h < 3 || h >= 16 || !_fil_used[h]) return -1;
    FSIZE_t pos;
    switch (whence) {
    case 0: pos = (FSIZE_t)a->offset; break;
    case 1: pos = f_tell(&_fil[h]) + (FSIZE_t)a->offset; break;
    case 2: pos = f_size(&_fil[h]) + (FSIZE_t)a->offset; break;
    default: return -1;
    }
    return (f_lseek(&_fil[h], pos) == FR_OK) ? 0 : -1;
}

static long _trap_ftell(int h) {
    if (h < 3 || h >= 16 || !_fil_used[h]) return -1;
    return (long)f_tell(&_fil[h]);
}

long trap_dispatch(long sc, long opt, long r255) {
    switch (sc) {
    case SC_HALT_I:
        for (;;);
    case SC_FOPEN_I:
        return _trap_fopen(opt, (const char*)r255);
    case SC_FCLOSE_I:
        return _trap_fclose((int)r255);
    case SC_FREAD_I:
        return _trap_fread((void*)r255);
    case SC_FWRITE_I:
        return _trap_fwrite((void*)r255);
    case SC_FSEEK_I:
        return _trap_fseek((void*)r255, (int)opt);
    case SC_FTELL_I:
        return _trap_ftell((int)r255);
    case SC_EXECVE_I:
        return proc_execve((const char*)r255);
    default:
        return -1;
    }
}
