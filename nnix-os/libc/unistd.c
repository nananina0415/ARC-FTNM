#include "unistd.h"
#include "../kernel/syscall.h"

#ifdef MMIX_SIM

int execve(const char* pathname, char* const argv[], char* const envp[]) {
    (void)argv; (void)envp;
    // mmixware 시뮬레이터는 execve 미지원
    return -1;
}

#else  // FPGA

#include "../kernel/sys.h"

// 커널 라이브러리를 직접 호출한다. 주석은 상주 OS에 TRAP으로 요청하던 코드다.
// #define FPGA_TRAP(ystr, zstr) \
//     __asm__ volatile("TRAP 1," ystr "," zstr : "+r"(r255))

int execve(const char* pathname, char* const argv[], char* const envp[]) {
    (void)argv; (void)envp;
    // register long r255 __asm__("$255") = (long)pathname;
    // FPGA_TRAP(SC_EXECVE, "0");
    long r255 = sys_execve(pathname);
    // 성공 시 여기에 도달하지 않음 (BIOS가 새 앱을 0번지부터 올리고 점프)
    return (int)r255;
}

#endif
