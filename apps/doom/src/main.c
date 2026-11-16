#define DOOM_IMPLEMENT_PRINT
#define DOOM_IMPLEMENT_MALLOC
#define DOOM_IMPLEMENT_FILE_IO
#define DOOM_IMPLEMENT_EXIT
#define DOOM_IMPLEMENTATION
#include "PureDOOM.h"

#include <string.h>
#include "syscall.h"
#include "sys.h"
#include "device/audio.h"
#include "device/usb.h"

// 커널 라이브러리를 직접 호출한다. 주석의 FPGA_TRAP 코드는 상주 OS에 TRAP으로 요청하던 코드다.
// #define FPGA_TRAP(ystr, zstr) \
//     __asm__ volatile("TRAP 1," ystr "," zstr : "+r"(r255) : : "memory")

/* getenv: libc getenv()는 항상 NULL 반환.
 * HOME이 NULL이면 doomrc 읽기 실패 → I_Error → g_exit=1. "."로 오버라이드. */
static char* cb_getenv(const char* var) {
    if (var[0]=='H'&&var[1]=='O'&&var[2]=='M'&&var[3]=='E'&&var[4]=='\0') return ".";
    return (void*)0;
}

/* gettime: gettimeofday가 libc에 미구현. FPGA 하드웨어 타이머에서 읽어야 함. */
static void cb_gettime(int* sec, int* usec) {
    /* TODO: FPGA 타이머 레지스터 읽기 */
    *sec  = 0;
    *usec = 0;
}

/* 사운드 이펙트: DOOM 내부 믹서 버퍼를 FPGA 오디오 믹서 DMA로 전달 */
static void do_audio(void)
{
    int16_t* buf = doom_get_sound_buffer();  // 버퍼 크기는 AUDIO_BUF_BYTES로 고정
    // struct { long buf; long len; } a = { (long)buf, AUDIO_BUF_BYTES };
    // register long r255 __asm__("$255") = (long)&a;
    // FPGA_TRAP(SC_AUDIO_WRITE, "0");
    sys_audio_write(buf, AUDIO_BUF_BYTES);
}

/* 음악: doom_tick_midi() 반환값 (reg<<8)|data 를 FPGA OPL3로 전달.
 * 140Hz 기준이나 게임 루프(35Hz)에서 드레인하므로 한 번에 여러 이벤트 처리. */
static void do_music(void)
{
    uint32_t msg;
    while ((msg = doom_tick_midi())) {
        // register long r255 __asm__("$255") = (long)msg;
        // FPGA_TRAP(SC_OPL_WRITE, "0");
        sys_opl_write(msg >> 8, msg & 0xFF);
    }
}

/* 아케이드 패널 입력 → DOOM 키 이벤트.
 * 버튼 의미는 화면 상태(게임/자동 지도/메뉴)마다 다르다.
 *
 *          게임                 자동 지도          메뉴
 *  스틱↑↓  전진/후진(↑↓)        지도 이동(↑↓)      항목 이동(↑↓)
 *  스틱←→  옆걸음(a/d 키)       지도 이동(←→)      슬라이더(←→)
 *          x를 누르면 회전(←→)
 *  c / d   좌/우회전(←→)        c: 전체(0)
 *  a       발사(Ctrl)           확대(=)            y 다음 Enter (질문엔 예, 메뉴엔 선택)
 *  b       달리기(Shift)        축소(-)            Backspace 다음 Space (메뉴엔 뒤로, 질문엔 아니오)
 *  w       상호작용(Space)      격자(g)
 *  x       회전 모드            표시(m)
 *  y / z   이전/다음 무기([ ])  y: 지우기(c)
 *  m       지도(Tab)            지도 닫기(Tab)
 *  p       메뉴(Esc)            메뉴(Esc)          메뉴 닫기 / 아니오(Esc)
 *
 * doom_key_down은 부를 때마다 눌림 이벤트를 보내므로 상태가 바뀐 키만 보낸다.
 * 화면 상태가 바뀌면 그때 눌려 있던 입력은 뗄 때까지 무시해서, 새 상태에서 다른 의미로 이어지지 않게 한다.
 */
#define STICK_LEFT  (1u << 16)
#define STICK_RIGHT (1u << 17)
#define STICK_UP    (1u << 18)
#define STICK_DOWN  (1u << 19)

enum { MODE_GAME, MODE_AUTOMAP, MODE_MENU };

#define MAX_WANTED_KEYS 16
static int want_keys[MAX_WANTED_KEYS];  // 이번 프레임에 눌려 있어야 할 키 (눌림 이벤트 순서대로)
static int want_count;
static unsigned char key_held[256];     // 지금 눌림 상태로 보낸 키

static void want(uint32_t input, uint32_t mask, doom_key_t key)
{
    if ((input & mask) && want_count < MAX_WANTED_KEYS)
        want_keys[want_count++] = (int)key;
}

static void doom_input(void)
{
    static int last_mode = MODE_GAME;
    static uint32_t ignored = 0;
    joystick_t js;
    uint32_t input = 0;

    if (usb_joystick(&js) == 0)
    {
        input = js.buttons & 0x3ffu;
        if (js.axis_x < 0) input |= STICK_LEFT;
        if (js.axis_x > 0) input |= STICK_RIGHT;
        if (js.axis_y < 0) input |= STICK_UP;
        if (js.axis_y > 0) input |= STICK_DOWN;
    }

    int mode = menuactive ? MODE_MENU : automapactive ? MODE_AUTOMAP : MODE_GAME;
    if (mode != last_mode)
    {
        ignored = input;
        last_mode = mode;
    }
    ignored &= input;
    input &= ~ignored;

    want_count = 0;
    want(input, STICK_UP,   DOOM_KEY_UP_ARROW);
    want(input, STICK_DOWN, DOOM_KEY_DOWN_ARROW);
    want(input, JOY_BTN_P,  DOOM_KEY_ESCAPE);
    if (mode == MODE_MENU)
    {
        want(input, STICK_LEFT,  DOOM_KEY_LEFT_ARROW);
        want(input, STICK_RIGHT, DOOM_KEY_RIGHT_ARROW);
        want(input, JOY_BTN_A,   DOOM_KEY_Y);
        want(input, JOY_BTN_A,   DOOM_KEY_ENTER);
        want(input, JOY_BTN_B,   DOOM_KEY_BACKSPACE);
        want(input, JOY_BTN_B,   DOOM_KEY_SPACE);
    }
    else if (mode == MODE_AUTOMAP)
    {
        want(input, STICK_LEFT,  DOOM_KEY_LEFT_ARROW);
        want(input, STICK_RIGHT, DOOM_KEY_RIGHT_ARROW);
        want(input, JOY_BTN_A,   DOOM_KEY_EQUALS);
        want(input, JOY_BTN_B,   DOOM_KEY_MINUS);
        want(input, JOY_BTN_C,   DOOM_KEY_0);
        want(input, JOY_BTN_W,   DOOM_KEY_G);
        want(input, JOY_BTN_X,   DOOM_KEY_M);
        want(input, JOY_BTN_Y,   DOOM_KEY_C);
        want(input, JOY_BTN_M,   DOOM_KEY_TAB);
    }
    else
    {
        uint32_t turn_mode = input & JOY_BTN_X;
        want(turn_mode ? 0 : input, STICK_LEFT,  DOOM_KEY_A);  // key_strafeleft
        want(turn_mode ? 0 : input, STICK_RIGHT, DOOM_KEY_D);  // key_straferight
        want(turn_mode ? input : 0, STICK_LEFT,  DOOM_KEY_LEFT_ARROW);
        want(turn_mode ? input : 0, STICK_RIGHT, DOOM_KEY_RIGHT_ARROW);
        want(input, JOY_BTN_C,   DOOM_KEY_LEFT_ARROW);
        want(input, JOY_BTN_D,   DOOM_KEY_RIGHT_ARROW);
        want(input, JOY_BTN_A,   DOOM_KEY_CTRL);
        want(input, JOY_BTN_B,   DOOM_KEY_SHIFT);
        want(input, JOY_BTN_W,   DOOM_KEY_SPACE);
        want(input, JOY_BTN_Y,   DOOM_KEY_LEFT_BRACKET);   // key_prevweapon
        want(input, JOY_BTN_Z,   DOOM_KEY_RIGHT_BRACKET);  // key_nextweapon
        want(input, JOY_BTN_M,   DOOM_KEY_TAB);
    }

    unsigned char wanted[256] = {0};
    int i;
    for (i = 0; i < want_count; i++)
        wanted[want_keys[i]] = 1;
    for (i = 0; i < 256; i++)
        if (key_held[i] && !wanted[i])
        {
            doom_key_up((doom_key_t)i);
            key_held[i] = 0;
        }
    for (i = 0; i < want_count; i++)
        if (!key_held[want_keys[i]])
        {
            doom_key_down((doom_key_t)want_keys[i]);
            key_held[want_keys[i]] = 1;
        }
}

int main(int argc, char** argv)
{
    doom_set_getenv(cb_getenv);
    doom_set_gettime(cb_gettime);

    /* 조이스틱용 키 바인딩: 이동/회전은 화살표 기본값 유지, 스트레이프만 A/D로 설정 */
    doom_set_default_int("key_strafeleft",  DOOM_KEY_A);
    doom_set_default_int("key_straferight", DOOM_KEY_D);
    doom_set_default_int("mouse_move",      0);

    doom_init(argc, argv, 0);

    while (1)
    {
        doom_input();

        doom_update();
        
        do_audio();
        do_music();
        // register long r255 __asm__("$255") = (long)doom_get_framebuffer(3 /* RGB */);
        // FPGA_TRAP(SC_VIDEO_PRESENT, SCO_VIDEO_HDMI);
        sys_video_present(1 /* SCO_VIDEO_HDMI */, doom_get_framebuffer(3 /* RGB */));
    }

    return 0;
}
