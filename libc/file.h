#ifndef _FILE_H
#define _FILE_H

#include "fatfs/ff.h"

// mmixware 핸들 번호 기준 (0=stdin, 1=stdout, 2=stderr, 3~= 일반 파일)
#define FD_STDIN   0
#define FD_STDOUT  1
#define FD_STDERR  2
#define MAX_FILES  16

// 열린 파일 슬롯. 핸들 번호로 인덱싱
typedef struct {
    FIL  fil;   // FatFs 파일 객체
    int  used;  // 슬롯 사용 중 여부
} file_slot_t;

extern file_slot_t file_table[MAX_FILES];

// trap.c에서 TRAP 디스패치 후 호출하는 함수들
// slot: mmixware 핸들 번호 (Z 바이트)
int  file_open (int slot, const char* path, const char* mode);
int  file_close(int slot);
long file_read (int slot, void* buf, long size, long count);
long file_write(int slot, const void* buf, long size, long count);
int  file_seek (int slot, long offset, int whence);
long file_tell (int slot);

#endif
