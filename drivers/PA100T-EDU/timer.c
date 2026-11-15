#include "../../nnix-os/kernel/device/types.h"
#include "../../nnix-os/kernel/device/timer.h"

/* 타이머 드라이버 미구현.
 * 보드 맵의 DEV_TIMER(블록 34) 레지스터 구조가 FPGA 쪽에서 정해지면 실제로 읽는다.
 * 그 전까지는 항상 0초를 반환한다. */

void timer_init(void) {}

void timer_get(int* sec_out, int* usec_out) {
    *sec_out  = 0;
    *usec_out = 0;
}
