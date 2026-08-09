#include "unistd.h"
#include "../src/syscall.h"

#ifdef MMIX_SIM

int execve(const char* pathname, char* const argv[], char* const envp[]) {
    (void)argv; (void)envp;
    // mmixware 시뮬레이터는 execve 미지원
    return -1;
}

#else  // FPGA

#define FPGA_TRAP(ystr, zstr) \
    __asm__ volatile("TRAP 1," ystr "," zstr : "+r"(r255))

int execve(const char* pathname, char* const argv[], char* const envp[]) {
    (void)argv; (void)envp;
    register long r255 __asm__("$255") = (long)pathname;
    FPGA_TRAP(SC_EXECVE, "0");
    // 성공 시 여기에 도달하지 않음 (OS가 DDR3 재구성 후 새 앱으로 점프)
    return (int)r255;
}

#endif
