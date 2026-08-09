#ifndef _TRAP_H
#define _TRAP_H

// 시스템콜 번호 — 정수 (트랩 핸들러 switch 디스패치용)
#define SC_HALT_I    0
#define SC_FOPEN_I   1
#define SC_FCLOSE_I  2
#define SC_FREAD_I   3
#define SC_FGETS_I   4
#define SC_FGETWS_I  5
#define SC_FWRITE_I  6
#define SC_FPUTS_I   7
#define SC_FPUTWS_I  8
#define SC_FSEEK_I   9
#define SC_FTELL_I  10

// 시스템콜 번호 — 어셈블리 즉값 문자열 (TRAP 명령어 Y 바이트 자리)
#define SC_HALT    "0"
#define SC_FOPEN   "1"
#define SC_FCLOSE  "2"
#define SC_FREAD   "3"
#define SC_FWRITE  "6"
#define SC_FSEEK   "9"
#define SC_FTELL  "10"

// TRAP Z 바이트 옵션 — Fopen 모드
#define SCO_FOPEN_R   "0"
#define SCO_FOPEN_W   "1"
#define SCO_FOPEN_A   "2"
#define SCO_FOPEN_RP  "3"
#define SCO_FOPEN_WP  "4"
#define SCO_FOPEN_AP  "5"

// TRAP Z 바이트 옵션 — Fseek whence
#define SCO_SEEK_SET  "0"
#define SCO_SEEK_CUR  "1"
#define SCO_SEEK_END  "2"

#endif
