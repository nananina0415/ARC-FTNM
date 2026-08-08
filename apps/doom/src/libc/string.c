#include "string.h"

void* memcpy(void* dst, const void* src, size_t n) {
    unsigned char*       d = dst;
    const unsigned char* s = src;
    while (n--) *d++ = *s++;
    return dst;
}

void* memmove(void* dst, const void* src, size_t n) {
    unsigned char*       d = dst;
    const unsigned char* s = src;
    if (d < s) {
        while (n--) *d++ = *s++;
    } else {
        d += n; s += n;
        while (n--) *--d = *--s;
    }
    return dst;
}

void* memset(void* dst, int c, size_t n) {
    unsigned char* d = dst;
    while (n--) *d++ = (unsigned char)c;
    return dst;
}

int memcmp(const void* a, const void* b, size_t n) {
    const unsigned char* p = a;
    const unsigned char* q = b;
    while (n--) {
        if (*p != *q) return *p - *q;
        p++; q++;
    }
    return 0;
}
