#include "sys.h"
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

// ── 초기화 ────────────────────────────────────────────────────────

static FATFS _fs;
static int   _fs_ready    = 0;
static int   _video_ready = 0;

void kernel_init_fs(void) {
    if (_fs_ready) return;
    _fs_ready = 1;
    f_mount(&_fs, "", 1);
}

void kernel_init_video(void) {
    if (_video_ready) return;
    _video_ready = 1;
    hdmi_init();
    lcd_init();
}

// UART 출력 (DEV_UART MMIO 레지스터 직접 접근)
static void _uart_write(const char* buf, long len) {
    const mmio_entry_t* e = mmio_find(DEV_UART);
    if (!e) return;
    volatile long* tx = (volatile long*)mmio_base(e->block);
    for (long i = 0; i < len; i++)
        tx[0] = (long)(unsigned char)buf[i];
}

// ── 파일 ──────────────────────────────────────────────────────────

long sys_fopen(int mode, const char* path) {
    kernel_init_fs();
    int h;
    for (h = FD_STDERR + 1; h < MAX_FILES; h++)
        if (!file_table[h].used) break;
    if (h == MAX_FILES) return -1;
    static const char* const modes[] = {"r", "w", "a", "r+", "w+", "a+"};
    if (mode < 0 || mode > 5) return -1;
    return file_open(h, path, modes[mode]) == 0 ? h : -1;
}

long sys_fclose(int handle) {
    return file_close(handle);
}

long sys_fread(int handle, void* buf, long total) {
    return file_read(handle, buf, 1, total);
}

long sys_fwrite(int handle, const void* buf, long total) {
    if (handle == FD_STDOUT || handle == FD_STDERR) {
        _uart_write((const char*)buf, total);
        return total;
    }
    return file_write(handle, buf, 1, total);
}

long sys_fseek(int handle, long offset, int whence) {
    return file_seek(handle, offset, whence);
}

long sys_ftell(int handle) {
    return file_tell(handle);
}

// ── 프로그램 ──────────────────────────────────────────────────────

static long _execve(const char* path, long crash_count) {
    kernel_init_fs();
    FIL fil;
    if (f_open(&fil, path, FA_READ) != FR_OK) return -1;
    long exe_cluster = (long)fil.obj.sclust;
    f_close(&fil);
    return proc_execve(exe_cluster, 0, crash_count);
}

long sys_execve(const char* path) {
    return _execve(path, 0);
}

// 링크파일은 클러스터 번호로만 전달되므로 루트 디렉토리에서 같은 클러스터의 파일을 찾는다.
long sys_link_read(char* buf, long size, long cluster) {
    kernel_init_fs();
    DIR dir;
    FILINFO fi;
    if (f_opendir(&dir, "/") != FR_OK) return -1;
    FIL fil;
    int found = 0;
    while (f_readdir(&dir, &fi) == FR_OK && fi.fname[0] != '\0') {
        if (fi.fattrib & AM_DIR) continue;
        if (f_open(&fil, fi.fname, FA_READ) != FR_OK) continue;
        long cl = (long)fil.obj.sclust;
        if (cl == cluster) { found = 1; break; }
        f_close(&fil);
    }
    f_closedir(&dir);
    if (!found) return -1;
    UINT br = 0;
    f_read(&fil, (void*)buf, (UINT)size, &br);
    f_close(&fil);
    return (long)br;
}

// CPU 정지. 보드 전원을 끄는 장치가 없어 HALT로 대신한다.
__attribute__((noreturn)) static void _halt(void) {
    __asm__ volatile ("TRAP 0,0,0");
    for (;;);
}

// 앱이 끝나면 OS를 다시 올린다. nnix.os를 찾지 못하면 멈춘다.
void sys_exit(int status) {
    (void)status;
    _execve("nnix.os", 0);
    _halt();
}

extern long _crash_count;  // crt0.s: 로더가 넘긴 연속 크래시 횟수

void kernel_crash(void) {
    if (_crash_count >= 1) _halt();
    _execve("nnix.os", _crash_count + 1);
    _halt();
}

// ── 장치 ──────────────────────────────────────────────────────────

long sys_gettime(int* sec, int* usec) {
    timer_get(sec, usec);
    return 0;
}

/* dev: 0=기본, 1=HDMI, 2=LCD
 * 기본은 HDMI → LCD 순서로 시도. 드라이버가 -1 반환 시 다음 대안으로 넘어감. */
long sys_video_present(int dev, const void* buf) {
    kernel_init_video();
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

long sys_audio_write(const int16_t* buf, long len) {
    return (long)audio_write(buf, (int)len);
}

long sys_input_joystick(int dev, joystick_t* out) {
    switch (dev) {
    case 0:
    case 1: return (long)usb_joystick(out);
    default: return -1;
    }
}

long sys_opl_write(unsigned int reg, unsigned int data) {
    return (long)opl_write(reg, data);
}
