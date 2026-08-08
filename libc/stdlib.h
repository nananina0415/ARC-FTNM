#ifndef _STDLIB_H
#define _STDLIB_H

#include <stddef.h>

void* malloc(size_t size);
void  free(void* ptr);
void* realloc(void* ptr, size_t size);

static inline void exit(int status) {
    (void)status;
    __asm__ volatile ("TRAP 0,0,0");
    __builtin_unreachable();
}
static inline char* getenv(const char* name) { (void)name; return (char*)0; }

static inline int  abs(int n)   { return __builtin_abs(n); }
static inline long labs(long n) { return __builtin_labs(n); }

#endif
