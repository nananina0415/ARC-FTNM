#ifndef __DOOM_CONFIG_H__
#define __DOOM_CONFIG_H__


#if defined(WIN32)
#define DOOM_WIN32
#elif defined(__APPLE__)
#define DOOM_APPLE
#else
#define DOOM_LINUX
#endif


#include "DOOM.h"


// 선택 기능. 컴파일할 때 정의하면 켜지고, 정의하지 않으면 원본과 같게 동작한다.
//   DOOM_AUTOMAP_PAUSE     싱글플레이에서 자동 지도가 열려 있는 동안 게임 진행을 멈춘다.
//   DOOM_AUTOMAP_NO_FOLLOW 자동 지도 따라가기 모드를 쓰지 않는다(f 키 무시). 지도는 직접 이동한다.
//   DOOM_WEAPON_CYCLE      이전/다음 무기 전환 키 key_prevweapon('['), key_nextweapon(']')를 쓴다.
//                          갖고 있지 않거나 한 발 쏠 탄약이 없는 무기는 건너뛴다.


#define doom_abs(x) ((x) < 0 ? -(x) : (x))


extern char error_buf[260];
extern int doom_flags;
extern doom_print_fn doom_print;
extern doom_malloc_fn doom_malloc;
extern doom_free_fn doom_free;
extern doom_open_fn doom_open;
extern doom_close_fn doom_close;
extern doom_read_fn doom_read;
extern doom_write_fn doom_write;
extern doom_seek_fn doom_seek;
extern doom_tell_fn doom_tell;
extern doom_eof_fn doom_eof;
extern doom_gettime_fn doom_gettime;
extern doom_exit_fn doom_exit;
extern doom_getenv_fn doom_getenv;


const char* doom_itoa(int i, int radix);
const char* doom_ctoa(char c);
const char* doom_ptoa(void* p);
void doom_memset(void* ptr, int value, int num);
void* doom_memcpy(void* destination, const void* source, int num);
int doom_fprint(void* handle, const char* str);
int doom_strlen(const char* str);
char* doom_concat(char* dst, const char* src);
char* doom_strcpy(char* destination, const char* source);
char* doom_strncpy(char* destination, const char* source, int num);
int doom_strcmp(const char* str1, const char* str2);
int doom_strncmp(const char* str1, const char* str2, int n);
int doom_strcasecmp(const char* str1, const char* str2);
int doom_strncasecmp(const char* str1, const char* str2, int n);
int doom_atoi(const char* str);
int doom_atox(const char* str);
int doom_toupper(int c);


#endif
