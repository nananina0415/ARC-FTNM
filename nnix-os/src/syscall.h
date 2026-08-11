#ifndef _SYSCALL_H
#define _SYSCALL_H

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
#define SC_EXECVE_I 11
#define SC_GETTIME_I        12
#define SC_VIDEO_PRESENT_I  13
#define SC_AUDIO_WRITE_I    14
#define SC_INPUT_JOYSTICK_I 15
#define SC_OPL_WRITE_I      17

// 시스템콜 번호 — 어셈블리 즉값 문자열 (TRAP 명령어 Y 바이트 자리)
#define SC_HALT             "0"
#define SC_FOPEN            "1"
#define SC_FCLOSE           "2"
#define SC_FREAD            "3"
#define SC_FGETS            "4"
#define SC_FGETWS           "5"
#define SC_FWRITE           "6"
#define SC_FPUTS            "7"
#define SC_FPUTWS           "8"
#define SC_FSEEK            "9"
#define SC_FTELL            "10"
#define SC_EXECVE           "11"
#define SC_GETTIME          "12"
#define SC_VIDEO_PRESENT    "13"
#define SC_AUDIO_WRITE      "14"
#define SC_INPUT_JOYSTICK   "15"
#define SC_OPL_WRITE        "16"

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

// TRAP Z 바이트 옵션 — SC_VIDEO_PRESENT 출력 장치
#define SCO_VIDEO_DEFAULT  "0"   // OS 기본 비디오 장치
#define SCO_VIDEO_HDMI     "1"   // HDMI 출력
#define SCO_VIDEO_LCD      "2"   // FPC LCD 출력

// TRAP Z 바이트 옵션 — SC_AUDIO_WRITE 채널 포맷
#define SCO_AUDIO_PCM16    "0"   // 16비트 PCM 스테레오 (기본)

// TRAP Z 바이트 옵션 — SC_INPUT_JOYSTICK 입력 장치
#define SCO_INPUT_DEFAULT  "0"   // OS 기본 입력 장치
#define SCO_INPUT_USB      "1"   // USB HID 조이스틱

#endif
