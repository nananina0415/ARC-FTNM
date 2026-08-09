#include "stdio.h"
#include "_trap.h"
#include "_print.h"
#include <stdarg.h>
#include <stddef.h>

static FILE _pool[16] = {
    [0] = {0, 1, 0},
    [1] = {1, 1, 0},
    [2] = {2, 1, 0},
};

FILE* const stdin  = &_pool[0];
FILE* const stdout = &_pool[1];
FILE* const stderr = &_pool[2];

// =============================================================================
#ifdef MMIX_SIM
// =============================================================================
// mmixware TRAP 규약 (X=0):
//   Fopen=1  Fclose=2  Fread=3  Fwrite=6  Fseek=9  Ftell=10
//   Z 바이트는 즉값(컴파일 타임) → 핸들별 case로 16개 전개
//   3-인자(Fopen/Fread/Fwrite): $255 = &{arg2, arg3}
//   2-인자(Fseek): $255 = offset 값 직접 전달
//   1-인자(Fclose/Ftell): Z = handle만

// "+r"(r255): r255를 입출력 레지스터로 지정. TRAP 전 $255에 넣고 후에 꺼냄.
// 없으면 GCC가 TRAP 결과를 최적화로 버릴 수 있음.
#define SIM_TRAP(ystr, h) \
    switch (h) { \
    case  0: __asm__ volatile("TRAP 0," ystr ",0"  : "+r"(r255)); break; \
    case  1: __asm__ volatile("TRAP 0," ystr ",1"  : "+r"(r255)); break; \
    case  2: __asm__ volatile("TRAP 0," ystr ",2"  : "+r"(r255)); break; \
    case  3: __asm__ volatile("TRAP 0," ystr ",3"  : "+r"(r255)); break; \
    case  4: __asm__ volatile("TRAP 0," ystr ",4"  : "+r"(r255)); break; \
    case  5: __asm__ volatile("TRAP 0," ystr ",5"  : "+r"(r255)); break; \
    case  6: __asm__ volatile("TRAP 0," ystr ",6"  : "+r"(r255)); break; \
    case  7: __asm__ volatile("TRAP 0," ystr ",7"  : "+r"(r255)); break; \
    case  8: __asm__ volatile("TRAP 0," ystr ",8"  : "+r"(r255)); break; \
    case  9: __asm__ volatile("TRAP 0," ystr ",9"  : "+r"(r255)); break; \
    case 10: __asm__ volatile("TRAP 0," ystr ",10" : "+r"(r255)); break; \
    case 11: __asm__ volatile("TRAP 0," ystr ",11" : "+r"(r255)); break; \
    case 12: __asm__ volatile("TRAP 0," ystr ",12" : "+r"(r255)); break; \
    case 13: __asm__ volatile("TRAP 0," ystr ",13" : "+r"(r255)); break; \
    case 14: __asm__ volatile("TRAP 0," ystr ",14" : "+r"(r255)); break; \
    case 15: __asm__ volatile("TRAP 0," ystr ",15" : "+r"(r255)); break; \
    }

FILE* fopen(const char* path, const char* mode) {
    int i;
    for (i = 3; i < 16; i++)
        if (!_pool[i].used) break;
    if (i == 16) return (FILE*)0;
    struct { long path; long mode; } a = { (long)path, (long)mode };
    register long r255 __asm__("$255") = (long)&a;
    SIM_TRAP(SC_FOPEN, i)
    if (r255 != 0) return (FILE*)0;
    _pool[i].handle = i;
    _pool[i].used   = 1;
    _pool[i].eof    = 0;
    return &_pool[i];
}

int fclose(FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16) return EOF;
    register long r255 __asm__("$255") = 0;
    SIM_TRAP(SC_FCLOSE, f->handle)
    f->used = 0;
    return (r255 < 0) ? EOF : 0;
}

size_t fread(void* buf, size_t size, size_t count, FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16 || !size || !count) return 0;
    long total = (long)size * (long)count;
    struct { long buf; long total; } a = { (long)buf, total };
    register long r255 __asm__("$255") = (long)&a;
    SIM_TRAP(SC_FREAD, f->handle)
    // r255 = n_read - total (0이면 전부 읽음, 음수면 부분/오류)
    long n_read = total + r255;
    if (n_read < 0) { f->eof = 1; return 0; }
    if (n_read < total) f->eof = 1;
    return (size_t)(n_read / (long)size);
}

size_t fwrite(const void* buf, size_t size, size_t count, FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16 || !size || !count) return 0;
    long total = (long)size * (long)count;
    struct { long buf; long total; } a = { (long)buf, total };
    register long r255 __asm__("$255") = (long)&a;
    SIM_TRAP(SC_FWRITE, f->handle)
    // r255 = 0이면 전부 씀, 음수면 못 쓴 바이트 수
    long written = (r255 == 0) ? total : (total + r255);
    if (written < 0) written = 0;
    return (size_t)(written / (long)size);
}

int fseek(FILE* f, long offset, int whence) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16) return -1;
    f->eof = 0;
    register long r255 __asm__("$255");
    long mw_off;
    if (whence == SEEK_CUR) {
        // mmixware는 SEEK_CUR 미지원 → Ftell로 현재 위치 얻어 합산
        r255 = 0;
        SIM_TRAP(SC_FTELL, f->handle)
        mw_off = r255 + offset;
    } else if (whence == SEEK_END) {
        // mmixware SEEK_END 인코딩: -(실제 offset + 1)
        mw_off = -(offset + 1);
    } else {
        mw_off = offset;
    }
    r255 = mw_off;
    SIM_TRAP(SC_FSEEK, f->handle)
    return (r255 < 0) ? -1 : 0;
}

long ftell(FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16) return -1L;
    register long r255 __asm__("$255") = 0;
    SIM_TRAP(SC_FTELL, f->handle)
    return r255;
}

int feof(FILE* f) {
    return (!f || !f->used) ? 1 : f->eof;
}

// =============================================================================
#else   // FPGA
// =============================================================================
// 커스텀 TRAP 규약 (X=1):
//   Y = 함수 번호 (mmixware enum과 동일)
//   Z = 옵션 바이트 (Fopen: 오픈 모드, Fseek: whence, 나머지: 0)
//   $255 in = 주 인자,  $255 out = 결과

// "+r"(r255): r255를 입출력 레지스터로 지정. TRAP 전 $255에 넣고 후에 꺼냄.
// 없으면 GCC가 TRAP 결과를 최적화로 버릴 수 있음.
#define FPGA_TRAP(ystr, zstr) \
    __asm__ volatile("TRAP 1," ystr "," zstr : "+r"(r255))

FILE* fopen(const char* path, const char* mode) {
    int i;
    for (i = 3; i < 16; i++)
        if (!_pool[i].used) break;
    if (i == 16) return (FILE*)0;

    register long r255 __asm__("$255") = (long)path;

    // FatFs는 항상 바이너리 모드 → t, b 플래그 무시
    // mode[1]이 '\0'이면 mode[2] 접근 UB → 단락 평가로 방지
    int plus = (mode[1] == '+' || (mode[1] != '\0' && mode[2] == '+'));
    // Z는 즉값이므로 모드별 switch 필요
    switch (mode[0]) {
    case 'r': if (plus) FPGA_TRAP(SC_FOPEN, SCO_FOPEN_RP); else FPGA_TRAP(SC_FOPEN, SCO_FOPEN_R); break;
    case 'w': if (plus) FPGA_TRAP(SC_FOPEN, SCO_FOPEN_WP); else FPGA_TRAP(SC_FOPEN, SCO_FOPEN_W); break;
    case 'a': if (plus) FPGA_TRAP(SC_FOPEN, SCO_FOPEN_AP); else FPGA_TRAP(SC_FOPEN, SCO_FOPEN_A); break;
    }
    
    if (r255 < 0) return (FILE*)0;
    _pool[i].handle = (int)r255;
    _pool[i].used   = 1;
    _pool[i].eof    = 0;
    return &_pool[i];
}

int fclose(FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16) return EOF;
    register long r255 __asm__("$255") = (long)f->handle;
    FPGA_TRAP(SC_FCLOSE, "0");
    f->used = 0;
    return (r255 < 0) ? EOF : 0;
}

size_t fread(void* buf, size_t size, size_t count, FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16 || !size || !count) return 0;
    long total = (long)size * (long)count;
    struct { long buf; long total; long handle; } a = { (long)buf, total, (long)f->handle };
    register long r255 __asm__("$255") = (long)&a;
    FPGA_TRAP(SC_FREAD, "0");
    // r255 = 읽은 바이트 수, -1이면 오류
    if (r255 < 0) { f->eof = 1; return 0; }
    if (r255 < total) f->eof = 1;
    // 읽은 카운트 반환
    return (size_t)(r255 / (long)size);
}

size_t fwrite(const void* buf, size_t size, size_t count, FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16 || !size || !count) return 0;
    long total = (long)size * (long)count;
    struct { long buf; long total; long handle; } a = { (long)buf, total, (long)f->handle };
    register long r255 __asm__("$255") = (long)&a;
    FPGA_TRAP(SC_FWRITE, "0");
    if (r255 < 0) return 0;
    return (size_t)(r255 / (long)size);
}

int fseek(FILE* f, long offset, int whence) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16) return -1;
    f->eof = 0;
    struct { long handle; long offset; } a = { (long)f->handle, offset };
    register long r255 __asm__("$255") = (long)&a;
    switch (whence) {
        case SEEK_SET: FPGA_TRAP(SC_FSEEK, SCO_SEEK_SET); break;
        case SEEK_CUR: FPGA_TRAP(SC_FSEEK, SCO_SEEK_CUR); break;
        case SEEK_END: FPGA_TRAP(SC_FSEEK, SCO_SEEK_END); break;
    }
    return (r255 < 0) ? -1 : 0;
}

long ftell(FILE* f) {
    if (!f || !f->used || f->handle < 0 || f->handle >= 16) return -1L;
    register long r255 __asm__("$255") = (long)f->handle;
    FPGA_TRAP(SC_FTELL, "0");
    return r255;
}

int feof(FILE* f) {
    return (!f || !f->used) ? 1 : f->eof;
}

#endif  // MMIX_SIM / FPGA

// =============================================================================
// SIM/FPGA 공통 — print functions
// =============================================================================

// 포맷은 fmt_iter_t로, 스트림 출력은 여기서 fwrite() → TRAP
int vsnprintf(char* buf, size_t n, const char* fmt, va_list ap) {
    fmt_iter_t it;
    fmt_init(&it, fmt, ap);
    size_t i = 0;
    char ch[4]; int nb;
    while ((nb = fmt_next(&it, ch)) > 0) {
        if (i + (size_t)nb + 1 > n) break;
        for (int j = 0; j < nb; j++) buf[i++] = ch[j];
    }
    if (n > 0) buf[i] = '\0';
    return (int)i;
}

int sprintf(char* buf, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fmt_iter_t it;
    fmt_init(&it, fmt, ap);
    int i = 0;
    char ch[4]; int nb;
    while ((nb = fmt_next(&it, ch)) > 0)
        for (int j = 0; j < nb; j++) buf[i++] = ch[j];
    buf[i] = '\0';
    va_end(ap);
    return i;
}

// FWRITE 트랩 핸들러에서 0,1,2는 표준 입출력으로 취급
int vfprintf(FILE* stream, const char* fmt, va_list ap) {
    fmt_iter_t it;
    fmt_init(&it, fmt, ap);
    char tmp[64];
    int total = 0, ti = 0;
    char ch[4]; int nb;
    while ((nb = fmt_next(&it, ch)) > 0) {
        if (ti + nb > (int)sizeof(tmp)) {
            fwrite(tmp, 1, (size_t)ti, stream);
            total += ti; ti = 0;
        }
        for (int j = 0; j < nb; j++)
            tmp[ti++] = ch[j];
    }
    if (ti > 0) { fwrite(tmp, 1, (size_t)ti, stream); total += ti; }
    return total;
}

int fprintf(FILE* stream, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = vfprintf(stream, fmt, ap);
    va_end(ap);
    return r;
}

int printf(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = vfprintf(stdout, fmt, ap);
    va_end(ap);
    return r;
}
