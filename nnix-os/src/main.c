#include "fatfs/ff.h"
#include "proc.h"
#include "device/hdmi.h"
#include "device/usb.h"
#include <stdint.h>
#include <string.h>

/* ── 앱 목록 상수 ──────────────────────────────────────────────── */
#define MAX_APPS      32
#define MAX_ARGC      16
#define LINK_CONTENT  256

/* ── 폰트·스케일 ─────────────────────────────────────────────── */
#define FONT_W        8
#define FONT_H        16
#define ITEM_SCALE    1
#define TITLE_SCALE   2
#define CHAR_W        (FONT_W * ITEM_SCALE)
#define CHAR_H        (FONT_H * ITEM_SCALE)

/* ── 박스 레이아웃 ─────────────────────────────────────────────── */
#define VISIBLE_ROWS  8
#define BOX_INNER_PX  320                                    /* 내부 너비(픽셀) 고정 */
#define BOX_PAD_PX    4                                      /* 상하 내부 여백 */
#define BOX_W         (BOX_INNER_PX + 2)
#define BOX_H         (VISIBLE_ROWS * CHAR_H + BOX_PAD_PX * 2 + 2)
#define BOX_X         ((HDMI_WIDTH  - BOX_W) / 2)

/* ── 타이틀 ─────────────────────────────────────────────────────── */
#define TITLE_STR     "NNIX on MMIX"
#define TITLE_LEN     12                                     /* strlen(TITLE_STR) */
#define TITLE_CW      (FONT_W * TITLE_SCALE)
#define TITLE_CH      (FONT_H * TITLE_SCALE)
#define TITLE_PX_W    (TITLE_LEN * TITLE_CW)
#define TITLE_X       ((HDMI_WIDTH - TITLE_PX_W) / 2)

/* ── 수직 레이아웃 ───────────────────────────────────────────────── */
#define TITLE_GAP     16
#define BOX_GAP       16
#define TOTAL_H       (TITLE_CH + TITLE_GAP + BOX_H + BOX_GAP + CHAR_H)
#define LAYOUT_TOP    ((HDMI_HEIGHT - TOTAL_H) / 2)
#define TITLE_Y       LAYOUT_TOP
#define BOX_Y         (TITLE_Y + TITLE_CH + TITLE_GAP)
#define BOX_INNER_X   (BOX_X + 1)
#define BOX_INNER_Y   (BOX_Y + 1 + BOX_PAD_PX)
#define HINT_Y        (BOX_Y + BOX_H + BOX_GAP)

/* ── 타입 ─────────────────────────────────────────────────────── */
typedef struct {
    char filename[FF_LFN_BUF];
    int  is_link;
} app_entry_t;

/* ── Unifont 8×16 (ASCII 32-127) ──────────────────────────────── */
#include "unifont.h"

/* ── 정적 스토리지 ─────────────────────────────────────────────── */
static uint8_t     fb[HDMI_HEIGHT * HDMI_WIDTH * 3];
static app_entry_t apps[MAX_APPS];
static int         app_count;
static int         sel;
static int         scroll_top;
static char        link_buf[LINK_CONTENT];
static char*       link_argv[MAX_ARGC];

/* ── 문자열 유틸 ─────────────────────────────────────────────── */
static const char* str_dot(const char* s)
{
    while (*s && *s != '.') s++;
    return s;   /* '.' 또는 '\0' 위치 */
}

static int str_icmp(const char* a, const char* b)
{
    while (*a && *b) {
        char ca = *a, cb = *b;
        if (ca >= 'a' && ca <= 'z') ca -= 32;
        if (cb >= 'a' && cb <= 'z') cb -= 32;
        if (ca != cb) return ca - cb;
        a++; b++;
    }
    return (unsigned char)*a - (unsigned char)*b;
}

/* ── 디렉터리 스캔 ────────────────────────────────────────────── */
// 이름.link.app 또는 이름.app만 탐지. 이름에 점(.)이 들어가면 안 됨.
static void scan_apps(void)
{
    DIR     dir;
    FILINFO fi;
    app_count = 0;
    if (f_opendir(&dir, "/") != FR_OK) return;
    while (app_count < MAX_APPS) {
        if (f_readdir(&dir, &fi) != FR_OK || fi.fname[0] == '\0') break;
        if (fi.fattrib & AM_DIR) continue;
        const char* dot = str_dot(fi.fname);
        if (*dot == '\0') continue;
        if (str_icmp(dot + 1, "link.app") == 0)
            apps[app_count].is_link = 1;
        else if (str_icmp(dot + 1, "app") == 0)
            apps[app_count].is_link = 0;
        else
            continue;

        strncpy(apps[app_count].filename, fi.fname, FF_LFN_BUF - 1);
        apps[app_count].filename[FF_LFN_BUF - 1] = '\0';
        app_count++;
    }
    f_closedir(&dir);
}

/* ── 렌더링 ─────────────────────────────────────────────────── */
static void fb_fill_rect(int x, int y, int w, int h,
                          uint8_t r, uint8_t g, uint8_t b)
{
    for (int py = y; py < y + h && (unsigned)py < HDMI_HEIGHT; py++)
        for (int px = x; px < x + w && (unsigned)px < HDMI_WIDTH; px++) {
            int i = (py * HDMI_WIDTH + px) * 3;
            fb[i] = r; fb[i+1] = g; fb[i+2] = b;
        }
}

static void fb_putchar_s(int x, int y, char c, int scale,
                          uint8_t r, uint8_t g, uint8_t b)
{
    if (c < 32 || c > 127) c = ' ';
    const uint8_t* glyph = _font[(uint8_t)c - 32];
    for (int gy = 0; gy < FONT_H; gy++)
        for (int gx = 0; gx < FONT_W; gx++) {
            if (!(glyph[gy] & (0x80u >> gx))) continue;
            for (int sy = 0; sy < scale; sy++)
                for (int sx = 0; sx < scale; sx++) {
                    int px = x + gx * scale + sx;
                    int py = y + gy * scale + sy;
                    if ((unsigned)px >= HDMI_WIDTH || (unsigned)py >= HDMI_HEIGHT) continue;
                    int i = (py * HDMI_WIDTH + px) * 3;
                    fb[i] = r; fb[i+1] = g; fb[i+2] = b;
                }
        }
}

static void fb_puts_s(int x, int y, const char* s, int scale,
                       uint8_t r, uint8_t g, uint8_t b)
{
    while (*s) { fb_putchar_s(x, y, *s++, scale, r, g, b); x += FONT_W * scale; }
}

static void fb_puts_todot_s(int x, int y, const char* s, int scale,
                              uint8_t r, uint8_t g, uint8_t b)
{
    const char* end = str_dot(s);
    while (s < end) { fb_putchar_s(x, y, *s++, scale, r, g, b); x += FONT_W * scale; }
}

static void fb_puts_center(int y, const char* s, int scale,
                            uint8_t r, uint8_t g, uint8_t b)
{
    int x = (HDMI_WIDTH - (int)strlen(s) * FONT_W * scale) / 2;
    fb_puts_s(x, y, s, scale, r, g, b);
}

static void render_menu(void)
{
    memset(fb, 0x10, sizeof(fb));

    /* 타이틀 */
    fb_puts_s(TITLE_X, TITLE_Y, TITLE_STR, TITLE_SCALE, 0xFF, 0xA0, 0x00);

    /* 박스 테두리 */
    fb_fill_rect(BOX_X,             BOX_Y,             BOX_W, 1,     0x60, 0x60, 0x60);
    fb_fill_rect(BOX_X,             BOX_Y + BOX_H - 1, BOX_W, 1,     0x60, 0x60, 0x60);
    fb_fill_rect(BOX_X,             BOX_Y,             1,     BOX_H, 0x60, 0x60, 0x60);
    fb_fill_rect(BOX_X + BOX_W - 1, BOX_Y,             1,     BOX_H, 0x60, 0x60, 0x60);

    if (app_count == 0) {
        fb_puts_s(BOX_INNER_X + 4, BOX_INNER_Y, "No apps found.",
                  ITEM_SCALE, 0x80, 0x80, 0x80);
    } else {
        int td    = (scroll_top > 0);
        int cap   = VISIBLE_ROWS - td;
        int bd    = (scroll_top + cap < app_count);
        int shown = cap - bd;

        if (td)
            fb_puts_s(BOX_INNER_X + 4, BOX_INNER_Y,
                      "...", ITEM_SCALE, 0x80, 0x80, 0x80);

        for (int i = 0; i < shown; i++) {
            int idx = scroll_top + i;
            if (idx >= app_count) break;
            int ry  = BOX_INNER_Y + (td + i) * CHAR_H;
            if (idx == sel) {
                fb_fill_rect(BOX_INNER_X, ry, BOX_INNER_PX, CHAR_H, 0xFF, 0xFF, 0xFF);
                fb_puts_todot_s(BOX_INNER_X + 4, ry, apps[idx].filename,
                                ITEM_SCALE, 0x10, 0x10, 0x10);
            } else {
                fb_puts_todot_s(BOX_INNER_X + 4, ry, apps[idx].filename,
                                ITEM_SCALE, 0xC0, 0xC0, 0xC0);
            }
        }

        if (bd)
            fb_puts_s(BOX_INNER_X + 4, BOX_INNER_Y + (td + shown) * CHAR_H,
                      "...", ITEM_SCALE, 0x80, 0x80, 0x80);
    }

    /* 힌트 */
    fb_puts_center(HINT_Y, "Joy U/D: Move   Btn 1: Run", ITEM_SCALE, 0x60, 0x60, 0x60);

    hdmi_present(fb, HDMI_WIDTH, HDMI_HEIGHT);
}

/* ── 스크롤 조정 ─────────────────────────────────────────────── */
static void adjust_scroll(void)
{
    /* sel이 창 아래로 벗어나면 scroll_top을 올림 */
    while (1) {
        int td    = (scroll_top > 0);
        int cap   = VISIBLE_ROWS - td;
        int bd    = (scroll_top + cap < app_count);
        int shown = cap - bd;
        if (sel <= scroll_top + shown - 1) break;
        scroll_top++;
    }
    /* sel이 창 위로 벗어나면 scroll_top을 내림 */
    if (sel < scroll_top) scroll_top = sel;
}

/* ── .link.app 파싱 및 실행 ──────────────────────────────────── */
static void exec_link(app_entry_t* e)
{
    FIL  fil;
    UINT br;
    if (f_open(&fil, e->filename, FA_READ) != FR_OK) return;
    f_read(&fil, link_buf, LINK_CONTENT - 1, &br);
    f_close(&fil);
    link_buf[br] = '\0';

    /* 토크나이즈: 공백 기준으로 분리해 link_argv[] 구성 */
    char* p = link_buf;
    int argc = 0;

    while (*p && argc < MAX_ARGC) {
        // 앞 공백 지우고
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
        if (!*p) break;
        // 단어 넣고
        link_argv[argc++] = p;
        // 뒷 공백 지우고
        while (*p && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') p++;
        // 끊어준다
        if (*p) *p++ = '\0';
    }
    if (argc == 0) return;

    proc_execve(link_argv[0], argc, link_argv);
}

static void exec_app(app_entry_t* e)
{
    link_argv[0] = e->filename;
    proc_execve(e->filename, 1, link_argv);
}

/* ── 진입점 ─────────────────────────────────────────────────── */
int main(void)
{
    static FATFS fs;
    f_mount(&fs, "", 1);
    hdmi_init();
    scan_apps();

    sel = 0;
    scroll_top = 0;
    render_menu();

    joystick_t prev = {0, 0, 0};
    for (;;) {
        joystick_t js;
        if (usb_joystick(&js) != 0) {
            prev.axis_y  = 0;
            prev.buttons = 0;
            continue;
        }

        int up   = (js.axis_y < -64) && !(prev.axis_y < -64);
        int down = (js.axis_y >  64) && !(prev.axis_y >  64);
        int ok   = (js.buttons & 1)  && !(prev.buttons & 1);
        prev = js;

        if (up   && sel > 0)             { sel--; adjust_scroll(); render_menu(); }
        if (down && sel < app_count - 1) { sel++; adjust_scroll(); render_menu(); }
        if (ok   && app_count > 0) {
            if (apps[sel].is_link) exec_link(&apps[sel]);
            else                   exec_app(&apps[sel]);
        }
    }
}
