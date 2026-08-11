#ifndef _DEVICE_OPL_H
#define _DEVICE_OPL_H

#include "types.h"

/* OPL3 FM 합성기 초기화 */
extern void opl_init(void);

/* OPL3 레지스터 쓰기. 성공 0, 미지원 -1.
 * reg: 9비트 레지스터 주소 (bit8=보조 뱅크), data: 8비트 값
 * doom_tick_midi() 반환값 형식: (reg << 8) | data */
extern int opl_write(unsigned int reg, unsigned int data);

#endif /* _DEVICE_OPL_H */
