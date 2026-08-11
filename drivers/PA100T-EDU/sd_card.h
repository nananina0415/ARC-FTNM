#ifndef _SD_CARD_H
#define _SD_CARD_H

#include <stdint.h>

/* SD 카드 초기화. 성공 0, 실패 -1 */
int sd_card_init(void);

/* 섹터 읽기. sector: LBA, buf: 512*count 바이트 버퍼. 성공 0 */
int sd_card_read(uint32_t sector, uint8_t* buf, uint32_t count);

/* 섹터 쓰기. 성공 0 */
int sd_card_write(uint32_t sector, const uint8_t* buf, uint32_t count);

/* 초기화 여부. 0: 미초기화, 1: 준비됨 */
int sd_card_status(void);

#endif
