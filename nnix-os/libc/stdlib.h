#ifndef _STDLIB_H
#define _STDLIB_H

#include <stddef.h>

void* malloc(size_t size);
void  free(void* ptr);
void* realloc(void* ptr, size_t size);

#ifdef MMIX_SIM
static inline void exit(int status) {
    (void)status;
    __asm__ volatile ("TRAP 0,0,0");  // mmixware 시뮬레이터의 Halt
    __builtin_unreachable();
}
#else
// FPGA: 커널 라이브러리가 nnix.os를 다시 실행한다.
// 주석은 TRAP 0,0,0으로 CPU를 멈추던 코드다.
// static inline void exit(int status) {
//     (void)status;
//     __asm__ volatile ("TRAP 0,0,0");
//     __builtin_unreachable();
// }
__attribute__((noreturn)) void sys_exit(int status);
static inline void exit(int status) {
    sys_exit(status);
}
#endif

// crt0.s가 main 반환 뒤 호출한다. exit와 같다.
__attribute__((noreturn)) void _crt_exit(long status);
static inline char* getenv(const char* name) { (void)name; return (char*)0; }

static inline int  abs(int n)   { return __builtin_abs(n); }
static inline long labs(long n) { return __builtin_labs(n); }

#endif
