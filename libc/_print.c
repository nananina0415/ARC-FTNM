#include "_print.h"

int _utoa(unsigned long val, char* buf, int base, int upper) {
    const char* dig = upper ? "0123456789ABCDEF" : "0123456789abcdef";
    if (val == 0) { buf[0] = '0'; buf[1] = '\0'; return 1; }
    char tmp[22];
    int i = 0;
    while (val > 0) { tmp[i++] = dig[val % (unsigned long)base]; val /= (unsigned long)base; }
    int j;
    for (j = 0; j < i; j++) buf[j] = tmp[i - 1 - j];
    buf[j] = '\0';
    return i;
}

int _itoa(long val, char* buf) {
    if (val < 0) {
        buf[0] = '-';
        // (unsigned long)0 - (unsigned long)val: LONG_MIN도 안전하게 처리
        return 1 + _utoa((unsigned long)0 - (unsigned long)val, buf + 1, 10, 0);
    }
    return _utoa((unsigned long)val, buf, 10, 0);
}

void fmt_init(fmt_iter_t* it, const char* fmt, va_list ap) {
    it->fmt = fmt;
    va_copy(it->ap, ap);
    it->str = (const char*)0;
    it->pos = 0;
    it->len = 0;
}

// 현재 토큰(tok 또는 str)에서 UTF-8 문자 하나 추출
static int _tok_next(fmt_iter_t* it, char out[4]) {
    if (it->str) {
        unsigned char c = (unsigned char)it->str[it->pos];
        int nb = (c < 0x80) ? 1 : (c < 0xE0) ? 2 : (c < 0xF0) ? 3 : 4;
        if (it->pos + nb > it->len) nb = it->len - it->pos;
        for (int i = 0; i < nb; i++) out[i] = it->str[it->pos + i];
        it->pos += nb;
        return nb;
    }
    out[0] = it->tok[it->pos++];
    return 1;
}

int fmt_next(fmt_iter_t* it, char out[4]) {
    for (;;) {
        // 현재 토큰 남은 것 먼저 소진
        if (it->pos < it->len)
            return _tok_next(it, out);

        if (*it->fmt == '\0') { va_end(it->ap); return 0; }

        // 리터럴 UTF-8 문자
        if (*it->fmt != '%') {
            unsigned char c = (unsigned char)*it->fmt;
            int nb = (c < 0x80) ? 1 : (c < 0xE0) ? 2 : (c < 0xF0) ? 3 : 4;
            for (int i = 0; i < nb; i++) out[i] = it->fmt[i];
            it->fmt += nb;
            return nb;
        }

        it->fmt++; // '%' 스킵

        // 플래그 스킵 (-, +, 공백, #, 0)
        while (*it->fmt == '-' || *it->fmt == '+' || *it->fmt == ' ' ||
               *it->fmt == '#' || *it->fmt == '0') it->fmt++;
        // 너비 스킵
        while (*it->fmt >= '0' && *it->fmt <= '9') it->fmt++;
        // 정밀도 스킵
        if (*it->fmt == '.') { it->fmt++; while (*it->fmt >= '0' && *it->fmt <= '9') it->fmt++; }
        // 길이 한정자 (l, ll, h, hh)
        int is_long = 0;
        if (*it->fmt == 'l') {
            is_long = 1; it->fmt++;
            if (*it->fmt == 'l') it->fmt++; // ll도 long으로 처리 (MMIX: long == long long)
        } else if (*it->fmt == 'h') {
            it->fmt++;
            if (*it->fmt == 'h') it->fmt++;
        }

        char spec = *it->fmt;
        if (spec) it->fmt++;

        it->str = (const char*)0;
        it->pos = 0;
        it->len = 0;

        switch (spec) {
        case 'd': case 'i': {
            long v = is_long ? va_arg(it->ap, long) : (long)va_arg(it->ap, int);
            it->len = _itoa(v, it->tok);
            break;
        }
        case 'u': {
            unsigned long v = is_long ? va_arg(it->ap, unsigned long)
                                      : (unsigned long)va_arg(it->ap, unsigned int);
            it->len = _utoa(v, it->tok, 10, 0);
            break;
        }
        case 'x': {
            unsigned long v = is_long ? va_arg(it->ap, unsigned long)
                                      : (unsigned long)va_arg(it->ap, unsigned int);
            it->len = _utoa(v, it->tok, 16, 0);
            break;
        }
        case 'X': {
            unsigned long v = is_long ? va_arg(it->ap, unsigned long)
                                      : (unsigned long)va_arg(it->ap, unsigned int);
            it->len = _utoa(v, it->tok, 16, 1);
            break;
        }
        case 'p': {
            unsigned long v = (unsigned long)va_arg(it->ap, void*);
            it->tok[0] = '0'; it->tok[1] = 'x';
            it->len = 2 + _utoa(v, it->tok + 2, 16, 0);
            break;
        }
        case 's': {
            const char* s = va_arg(it->ap, const char*);
            if (!s) s = "(null)";
            it->str = s;
            int n = 0; while (s[n]) n++;
            it->len = n;
            break;
        }
        case 'c':
            it->tok[0] = (char)va_arg(it->ap, int);
            it->len = 1;
            break;
        case '%':
            out[0] = '%';
            return 1;
        default:
            continue; // 알 수 없는 지정자 스킵
        }
        // 루프 상단으로 돌아가서 tok/str 소진
    }
}
