#include "../src/syscall.h"

// crt0.s 정의 전역 (BSS). 여기서 채운 후 crt0.s가 main에 전달
long  _crt_argc = 0;
long  _crt_argv = 0;  // char** 를 long으로 보관

// crt0.s에서 저장한 링크파일 FAT 클러스터 번호
extern long _link_cluster;

static char     _link_buf[256];
static char*    _argv[17];  // argv[0..15] + NULL sentinel

void _args_init(void) {
    if (_link_cluster == 0) {
        _crt_argc = 0;
        _crt_argv = 0;
        return;
    }

    struct { long buf; long size; long cluster; } req = {
        (long)_link_buf, 255, _link_cluster
    };
    register long r255 __asm__("$255") = (long)&req;
    __asm__ volatile("TRAP 1," SC_LINK_READ ",0" : "+r"(r255) : : "memory");
    long br = r255;
    if (br <= 0) {
        _crt_argc = 0;
        _crt_argv = 0;
        return;
    }
    _link_buf[br] = '\0';

    // 링크파일 형식: "exe_name arg1 arg2 ..."
    // 토큰 전체를 argv[]에 담음 (argv[0] = exe 이름)
    int argc = 0;
    char* p = _link_buf;
    while (*p && argc < 16) {
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
        if (!*p) break;
        _argv[argc++] = p;
        while (*p && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') p++;
        if (*p) *p++ = '\0';
    }
    _argv[argc] = (char*)0;

    _crt_argc = (long)argc;
    _crt_argv = (long)_argv;
}
