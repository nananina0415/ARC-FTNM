#ifndef _PRINT_H
#define _PRINT_H

#include <stdarg.h>
#include <stddef.h>

// TODO: 나중에 타입변환 파일 만들면 그쪽으로 옮길 것
// 정수 → 문자열 변환 (buf는 최소 22바이트, 반환값은 변환된 문자 수)
int _utoa(unsigned long val, char* buf, int base, int upper);
int _itoa(long val, char* buf);

// 포맷 이터레이터 상태
// str != NULL → str[pos]부터 출력 (%s)
// str == NULL → tok[pos]부터 출력 (숫자 등)
typedef struct {
    const char* fmt;
    va_list     ap;
    const char* str;
    char        tok[22];
    int         pos;
    int         len;
} fmt_iter_t;

void fmt_init(fmt_iter_t* it, const char* fmt, va_list ap);
int  fmt_next(fmt_iter_t* it, char out[4]); // UTF-8 문자 하나를 out에 씀, 반환값: 바이트 수, 0이면 끝

#endif
