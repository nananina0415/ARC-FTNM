#include "syscall.h"
#include "fs.h"
#include "proc.h"
#include "device/types.h"
#include "device/timer.h"
#include "device/opl.h"
#include "device/hdmi.h"
#include "device/lcd.h"
#include "device/audio.h"
#include "device/usb.h"
#include "fatfs/ff.h"

// UART 출력 (DEV_UART MMIO 레지스터 직접 접근)
static void _uart_write(const char* buf, long len) {
    const mmio_entry_t* e = mmio_find(DEV_UART);
    if (!e) return;
    volatile long* tx = (volatile long*)mmio_base(e->block);
    for (long i = 0; i < len; i++)
        tx[0] = (long)(unsigned char)buf[i];
}

static long _trap_fopen(long opt, const char* path) {
    int h;
    for (h = FD_STDERR + 1; h < MAX_FILES; h++)
        if (!file_table[h].used) break;
    if (h == MAX_FILES) return -1;
    static const char* const modes[] = {"r", "w", "a", "r+", "w+", "a+"};
    if (opt < 0 || opt > 5) return -1;
    return file_open(h, path, modes[opt]) == 0 ? h : -1;
}

static long _trap_fread(struct { long buf; long total; long handle; }* a) {
    return file_read((int)a->handle, (void*)a->buf, 1, a->total);
}

static long _trap_fwrite(struct { long buf; long total; long handle; }* a) {
    if (a->handle == FD_STDOUT || a->handle == FD_STDERR) {
        _uart_write((const char*)a->buf, a->total);
        return a->total;
    }
    return file_write((int)a->handle, (const void*)a->buf, 1, a->total);
}

static long _trap_fseek(struct { long handle; long offset; }* a, int whence) {
    return file_seek((int)a->handle, a->offset, whence);
}

static long _trap_gettime(struct { int sec; int usec; }* a) {
    timer_get(&a->sec, &a->usec);
    return 0;
}

/* opt: SCO_VIDEO_DEFAULT=0, SCO_VIDEO_HDMI=1, SCO_VIDEO_LCD=2
 * DEFAULT는 HDMI → LCD 순서로 시도. 드라이버가 -1 반환 시 다음 대안으로 넘어감. */
static long _trap_video_present(int dev, const void* buf) {
    int r;
    switch (dev) {
    case 0:
        r = hdmi_present((const uint8_t*)buf, HDMI_WIDTH, HDMI_HEIGHT);
        if (r == 0) return 0;
        return (long)lcd_present((const uint8_t*)buf, LCD_WIDTH, LCD_HEIGHT);
    case 1: return (long)hdmi_present((const uint8_t*)buf, HDMI_WIDTH, HDMI_HEIGHT);
    case 2: return (long)lcd_present((const uint8_t*)buf, LCD_WIDTH, LCD_HEIGHT);
    default: return -1;
    }
}

static long _trap_audio_write(struct { long buf; long len; }* a) {
    return (long)audio_write((const int16_t*)a->buf, (int)a->len);
}

static long _trap_input_joystick(int dev, joystick_t* out) {
    switch (dev) {
    case 0:
    case 1: return (long)usb_joystick(out);
    default: return -1;
    }
}

long trap_dispatch(long sc, long opt, long r255) {
    switch (sc) {
    case SC_HALT_I:
        for (;;);
    case SC_FOPEN_I:
        return _trap_fopen(opt, (const char*)r255);
    case SC_FCLOSE_I:
        return file_close((int)r255);
    case SC_FREAD_I:
        return _trap_fread((void*)r255);
    case SC_FWRITE_I:
        return _trap_fwrite((void*)r255);
    case SC_FSEEK_I:
        return _trap_fseek((void*)r255, (int)opt);
    case SC_FTELL_I:
        return file_tell((int)r255);
    case SC_EXECVE_I: {
        FIL fil;
        if (f_open(&fil, (const char*)r255, FA_READ) != FR_OK) return -1;
        long exe_cluster = (long)fil.obj.sclust;
        f_close(&fil);
        return proc_execve(exe_cluster, 0);
    }
    case SC_GETTIME_I:
        return _trap_gettime((void*)r255);
    case SC_VIDEO_PRESENT_I:
        return _trap_video_present((int)opt, (const void*)r255);
    case SC_AUDIO_WRITE_I:
        return _trap_audio_write((void*)r255);
    case SC_INPUT_JOYSTICK_I:
        return _trap_input_joystick((int)opt, (joystick_t*)r255);
    case SC_LINK_READ_I: {
        struct { long buf; long size; long cluster; }* a = (void*)r255;
        DIR dir;
        FILINFO fi;
        if (f_opendir(&dir, "/") != FR_OK) return -1;
        FIL fil;
        int found = 0;
        while (f_readdir(&dir, &fi) == FR_OK && fi.fname[0] != '\0') {
            if (fi.fattrib & AM_DIR) continue;
            if (f_open(&fil, fi.fname, FA_READ) != FR_OK) continue;
            long cl = (long)fil.obj.sclust;
            if (cl == a->cluster) { found = 1; break; }
            f_close(&fil);
        }
        f_closedir(&dir);
        if (!found) return -1;
        UINT br = 0;
        f_read(&fil, (void*)a->buf, (UINT)a->size, &br);
        f_close(&fil);
        return (long)br;
    }
    case SC_OPL_WRITE_I:
        /* r255 = doom_tick_midi() 반환값: (reg << 8) | data */
        return (long)opl_write((unsigned int)(r255 >> 8), (unsigned int)(r255 & 0xFF));
    default:
        return -1;
    }
}
