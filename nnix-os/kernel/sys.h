#ifndef _SYS_H
#define _SYS_H

// 앱과 OS가 정적 링크해 직접 호출하는 커널 API.
// 함수마다 syscall.h의 SC_* 번호 하나에 대응한다. 반환값 규약도 같다.

#include <stdint.h>
#include "device/usb.h"

// 처음 호출할 때 한 번만 초기화한다. 커널 함수들이 필요할 때 스스로 부른다.
void kernel_init_fs(void);     // SD 카드 FAT 마운트
void kernel_init_video(void);  // HDMI, LCD

// 파일. mode: 0=r 1=w 2=a 3=r+ 4=w+ 5=a+. 핸들 0~2는 표준 입출력.
long sys_fopen(int mode, const char* path);       // 핸들, 실패 -1
long sys_fclose(int handle);                       // 0, 실패 -1
long sys_fread(int handle, void* buf, long total); // 읽은 바이트 수, 실패 -1
long sys_fwrite(int handle, const void* buf, long total);
long sys_fseek(int handle, long offset, int whence);
long sys_ftell(int handle);

// 프로그램. 성공하면 반환하지 않는다.
long sys_execve(const char* path);
long sys_link_read(char* buf, long size, long cluster);  // 링크파일 내용, 읽은 바이트 수
__attribute__((noreturn)) void sys_exit(int status);    // nnix.os를 다시 실행

// 장치
long sys_gettime(int* sec, int* usec);
long sys_video_present(int dev, const void* buf);  // dev: 0=기본 1=HDMI 2=LCD
long sys_audio_write(const int16_t* buf, long len);
long sys_input_joystick(int dev, joystick_t* out);
long sys_opl_write(unsigned int reg, unsigned int data);

#endif
