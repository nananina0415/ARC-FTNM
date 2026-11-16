// 메모리 관련 공통 함수/변수

#ifndef _MEM_H
#define _MEM_H


#include <stdint.h>

// 프리/할당 리스트의 노드 하나. 16바이트 고정 크기.
typedef struct {
    uint64_t ptr;   // 힙 내 블록의 시작 주소
    uint32_t next;  // 같은 리스트 내 다음 노드의 인덱스 (NULL_IDX = 끝)
    uint32_t size;  // 블록 크기 (바이트)
} node_t;

// ── 메모리 배치 (FPGA) ──────────────────────────────────────────
//
//   0              코드·데이터·BSS
//   fix_end        BSS 끝. 링커 심볼 _fix_end, 실행파일 헤더에도 기록됨
//                  gap: 뒤쪽 1/4 구간을 캐시 라인에 맞추는 0~127바이트
//   unfix_start    = heap_start. 노드 풀·헤더(1MiB) 뒤에 블록 할당 영역
//     +1/2         heap_end. 여기부터 아래로 자라는 프로그램 스택 영역
//     +3/4         rS. rS-1부터 아래로 프로그램 스택, rS부터 위로 레지스터 스택
//   mem_size       RAM 끝
//
//   gap         = (mem_size - fix_end) mod 128
//   unfix_size  = mem_size - unfix_start            (128의 배수)
//   rS          = unfix_start + unfix_size * 3/4     (= mem_size - unfix_size/4)
//
// 로더(BIOS)가 이 식으로 rS를 계산해 UNSAVE로 넣고, mem_size와 함께 앱에 넘긴다.
// 앱은 crt0에서 rS를 _stack_base에 보존하고, 커널은 mem_size와 _stack_base로
// unfix_size = (mem_size - rS) * 4 를 되살려 힙 범위를 정한다.
//
// 시뮬 (-DMMIX_SIM): MMIX pool segment를 heap_start로 쓴다. 런타임 변수로 두어
//   컴파일러가 pool segment 상수를 GREG에 올리지 않도록 함.
//   MMIX GCC는 G=253 (g[253..255]만 전역)이라 g[254](스택포인터)와 충돌 방지 필수.

extern char      _fix_end[];   // 링커: BSS 끝
extern long      _mem_size;    // crt0: 로더가 넘긴 RAM 크기
extern long      _stack_base;  // crt0: 로더가 넣은 처음 rS
extern uintptr_t _heap_start;  // heap_init: unfix_start (시뮬은 pool segment)
extern uintptr_t _heap_end;    // heap_init: unfix_start + unfix_size/2

// 힙 영역 전체의 시작 주소 (노드 풀 + 헤더 + 블록 할당 영역)
#define HEAP_START (_heap_start)

#define NODE_SIZE        16                          // 노드 하나의 크기 (바이트)
#define NODE_POOL_SIZE   0x100000                    // 노드 풀 + 헤더 (1MiB = 65536 × 16)
#define NODE_BASE        (HEAP_START)                // 노드 풀 시작. node[i] = NODE_BASE + i*NODE_SIZE
#define HEAP_BLOCK_BASE  (HEAP_START + NODE_POOL_SIZE) // 실제 블록 할당이 일어나는 시작 주소
#define NULL_IDX   0u                                // 노드 없음을 나타내는 인덱스
#ifdef MMIX_SIM
// 시뮬: (RAM 크기 - HEAP_BLOCK_BASE) / 2
#  define HEAP_BLOCK_SIZE ((uint32_t)(((long)_mem_size - (long)HEAP_BLOCK_BASE) / 2))
#else
// FPGA: 힙 영역(unfix의 첫 1/2)에서 노드 풀을 뺀 나머지. 힙 영역이 노드 풀보다 작으면 0
#  define HEAP_BLOCK_SIZE ((uint32_t)(_heap_end > HEAP_BLOCK_BASE ? _heap_end - HEAP_BLOCK_BASE : 0))
#endif
#define MAX_NODES  65535u                      // 최대 노드 수 (인덱스 1~65535)

// HEAP_START 기준 헤더 오프셋
// +0x00~0x03: 비워둠 (NULL 포인터 영역)
// +0x04~0x0F: 세 리스트의 헤드 인덱스
#define ADDR_HEAD_FREE  (HEAP_START + (uintptr_t)0x04) // free list 헤드 인덱스 주소
#define ADDR_HEAD_ALLOC (HEAP_START + (uintptr_t)0x08) // alloc list 헤드 인덱스 주소
#define ADDR_HEAD_POOL  (HEAP_START + (uintptr_t)0x0C) // pool 헤드 인덱스 주소

// volatile: 컴파일러가 물리 주소 접근을 최적화로 제거하지 못하게 함
#define HEAD_FREE  (*(volatile uint32_t*)ADDR_HEAD_FREE)  // free list 첫 노드 인덱스
#define HEAD_ALLOC (*(volatile uint32_t*)ADDR_HEAD_ALLOC) // alloc list 첫 노드 인덱스
#define HEAD_POOL  (*(volatile uint32_t*)ADDR_HEAD_POOL)  // pool 첫 노드 인덱스

// 인덱스를 노드 포인터로 변환. uintptr_t 경유로 NULL 포인터 최적화 우회
static inline node_t* node_get(uint32_t idx) {
    return (node_t*)(NODE_BASE + (uintptr_t)idx * NODE_SIZE);
}

// 세 리스트와 노드 풀을 초기화. 첫 malloc 호출 전에 실행되어야 함
void heap_init(void);

// pool: 어느 리스트에도 속하지 않은 빈 노드 슬롯 관리
uint32_t node_pool_take(void);            // pool에서 슬롯 하나를 꺼내 인덱스 반환. 없으면 NULL_IDX
void     node_pool_return(uint32_t idx);  // 사용 끝난 노드 슬롯을 pool에 반환

// free list: 할당되지 않은 빈 블록 목록. 크기 오름차순 정렬
void     free_list_insert(uint32_t idx);                          // 삽입하면서 주소 인접 블록이 있으면 병합. free()에서 호출
void     free_list_remove(uint32_t idx, uint32_t prev);           // 노드 제거. prev는 직전 노드 인덱스
uint32_t free_list_find_fit(uint32_t total, uint32_t* prev_out);  // total 이상인 가장 작은 블록 탐색. prev_out에 직전 노드 인덱스 저장

// alloc list: 현재 할당 중인 블록 목록. 크기 오름차순 정렬
void     alloc_list_insert(uint32_t idx);                         // 크기 순서에 맞게 삽입
void     alloc_list_remove(uint32_t idx, uint32_t prev);          // 노드 제거. prev는 직전 노드 인덱스
uint32_t alloc_list_find(uint64_t ptr, uint32_t* prev_out);       // ptr 주소로 노드 탐색. prev_out에 직전 노드 인덱스 저장

#endif
