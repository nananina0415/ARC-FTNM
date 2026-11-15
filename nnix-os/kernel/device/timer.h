#ifndef _DEVICE_TIMER_H
#define _DEVICE_TIMER_H

#include "types.h"

// 타이머 초기화
extern void timer_init(void);

// 부팅 이후 경과 시간 반환
extern void timer_get(int* sec_out, int* usec_out);

#endif
