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

// 메모리 관리 영역 전체의 시작 주소 (노드 풀 + 헤더 + 힙 모두 포함)
// 내부 오프셋은 고정이고 이 베이스만 빌드 타겟에 따라 달라짐
// - 시뮬 (-DMMIX_SIM): MMIX pool segment 시작
// - raw binary: 링커가 BSS 끝 직후를 _mem_start로 정의
#ifdef MMIX_SIM
#  define MEM_BASE ((uintptr_t)0x8000000000000000ULL)
#else
extern char _mem_start[];
#  define MEM_BASE ((uintptr_t)_mem_start)
#endif

#define NODE_SIZE  16                          // 노드 하나의 크기 (바이트)
#define NODE_BASE  (MEM_BASE)                  // 노드 풀 시작. node[i] = NODE_BASE + i*NODE_SIZE
#define HEAP_BASE  (MEM_BASE + 0x100000)       // 실제 할당이 일어나는 힙 시작 주소
#define NULL_IDX   0u                          // 노드 없음을 나타내는 인덱스
#define HEAP_SIZE  ((uint32_t)0x20000000)      // 힙 크기 (512MB)
#define MAX_NODES  65535u                      // 최대 노드 수 (인덱스 1~65535)

// MEM_BASE 기준 헤더 오프셋
// +0x00~0x03: 비워둠 (NULL 포인터 영역)
// +0x04~0x0F: 세 리스트의 헤드 인덱스
#define ADDR_HEAD_FREE  (MEM_BASE + (uintptr_t)0x04) // free list 헤드 인덱스 주소
#define ADDR_HEAD_ALLOC (MEM_BASE + (uintptr_t)0x08) // alloc list 헤드 인덱스 주소
#define ADDR_HEAD_POOL  (MEM_BASE + (uintptr_t)0x0C) // pool 헤드 인덱스 주소

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
