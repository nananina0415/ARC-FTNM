#ifndef _DEVICE_AUDIO_H
#define _DEVICE_AUDIO_H

#include "types.h"
#include <stdint.h>

// DOOM 출력 사양: 11025Hz, 16비트, 스테레오, 512샘플 (버퍼 2048바이트)
#define AUDIO_SAMPLE_RATE 11025
#define AUDIO_CHANNELS    2
#define AUDIO_BITS        16
#define AUDIO_BUF_SAMPLES 512
#define AUDIO_BUF_BYTES   (AUDIO_BUF_SAMPLES * AUDIO_CHANNELS * (AUDIO_BITS / 8))

// 오디오 초기화 (MMIO/DMA 버퍼 설정 포함)
extern void audio_init(void);

// PCM 샘플을 오디오 출력 버퍼에 씀. 성공 0, 미지원 -1
// buf: 16비트 스테레오 인터리브, len: 바이트 수
extern int audio_write(const int16_t* buf, int len);

#endif
