#include "mem.h"
#ifndef MMIX_SIM
#include "sys.h"
#endif

#ifdef MMIX_SIM
/* Pool Segment(0x4000...) 내 heap metadata 시작 주소.
 * 컴파일 타임 상수 대신 런타임 변수로 선언: MMIX GCC가 이 주소를 GREG에 올리면
 * G=253 환경에서 g[254](스택포인터)와 충돌하기 때문. */
uintptr_t _heap_start = (uintptr_t)0x4000000002000000ULL;
uintptr_t _heap_end   = 0;
#else
uintptr_t _heap_start = 0;
uintptr_t _heap_end   = 0;
#endif

/* 로더가 넘긴 mem_size와 rS로 힙 범위(unfix의 첫 1/2)를 정한다. 배치는 mem.h 참고.
 * 값이 배치 규약에 맞지 않으면(rS가 RAM 밖이거나 힙이 BSS와 겹치면) 크래시로 처리한다. */
static void heap_layout_init(void) {
#ifndef MMIX_SIM
    uintptr_t mem_size  = (uintptr_t)_mem_size;
    uintptr_t rs        = (uintptr_t)_stack_base;
    uintptr_t fix_end   = (uintptr_t)_fix_end;
    if (rs <= fix_end || rs >= mem_size) kernel_crash();
    uintptr_t unfix_size  = (mem_size - rs) * 4;
    uintptr_t unfix_start = mem_size - unfix_size;
    if (unfix_size > mem_size || unfix_start < fix_end) kernel_crash();
    _heap_start = unfix_start;
    _heap_end   = unfix_start + unfix_size / 2;
#endif
}

static int heap_initialized = 0;

void heap_init(void) {
    if (heap_initialized) return;
    heap_initialized = 1;
    heap_layout_init();

    HEAD_ALLOC = NULL_IDX;
    HEAD_POOL  = 2;

    // 힙 영역이 노드 풀보다 작으면 free list가 비어 malloc은 항상 실패한다.
    uint32_t size = HEAP_BLOCK_SIZE;
    HEAD_FREE = size ? 1 : NULL_IDX;

    node_t* n1 = node_get(1);
    n1->ptr  = HEAP_BLOCK_BASE;
    n1->size = size;
    n1->next = NULL_IDX;

    for (uint32_t i = 2; i < MAX_NODES; i++)
        node_get(i)->next = i + 1;
    node_get(MAX_NODES)->next = NULL_IDX;
}

/* --- pool --- */

uint32_t node_pool_take(void) {
    uint32_t idx = HEAD_POOL;
    if (idx == NULL_IDX) return NULL_IDX;
    HEAD_POOL = node_get(idx)->next;
    return idx;
}

void node_pool_return(uint32_t idx) {
    node_t* n = node_get(idx);
    n->ptr  = 0;
    n->size = 0;
    n->next = HEAD_POOL;
    HEAD_POOL = idx;
}

/* --- free list (크기 오름차순) --- */

/* 삽입하면서 주소 인접 블록과 병합. free()에서 호출. */
void free_list_insert(uint32_t idx) {
    node_t*  n    = node_get(idx);
    uint32_t prev = NULL_IDX;
    uint32_t curr = HEAD_FREE;

    while (curr != NULL_IDX) {
        node_t*  c    = node_get(curr);
        uint32_t next = c->next;

        if (c->ptr + c->size == n->ptr) {
            /* 좌측 인접: 발견 즉시 떼서 n에 흡수 */
            if (prev == NULL_IDX) HEAD_FREE = next;
            else node_get(prev)->next = next;
            n->ptr   = c->ptr;
            n->size += c->size;
            node_pool_return(curr);
        } else if (n->ptr + n->size == c->ptr) {
            /* 우측 인접: 발견 즉시 떼서 n에 흡수 */
            if (prev == NULL_IDX) HEAD_FREE = next;
            else node_get(prev)->next = next;
            n->size += c->size;
            node_pool_return(curr);
        } else {
            prev = curr;
        }
        curr = next;
    }

    /* 크기 오름차순 위치에 삽입 */
    prev = NULL_IDX;
    curr = HEAD_FREE;
    while (curr != NULL_IDX) {
        if (n->size <= node_get(curr)->size) break;
        prev = curr;
        curr = node_get(curr)->next;
    }
    n->next = curr;
    if (prev == NULL_IDX) HEAD_FREE = idx;
    else node_get(prev)->next = idx;
}

void free_list_remove(uint32_t idx, uint32_t prev) {
    node_t* n = node_get(idx);
    if (prev == NULL_IDX) HEAD_FREE = n->next;
    else node_get(prev)->next = n->next;
    n->next = NULL_IDX;
}

uint32_t free_list_find_fit(uint32_t total, uint32_t* prev_out) {
    uint32_t prev = NULL_IDX;
    uint32_t curr = HEAD_FREE;

    while (curr != NULL_IDX) {
        node_t* c = node_get(curr);
        if (c->size >= total) {
            *prev_out = prev;
            return curr;
        }
        prev = curr;
        curr = c->next;
    }

    *prev_out = NULL_IDX;
    return NULL_IDX;
}

/* --- alloc list (크기 오름차순) --- */

void alloc_list_insert(uint32_t idx) {
    node_t* n    = node_get(idx);
    uint32_t prev = NULL_IDX;
    uint32_t curr = HEAD_ALLOC;

    while (curr != NULL_IDX) {
        if (n->size <= node_get(curr)->size) break;
        prev = curr;
        curr = node_get(curr)->next;
    }

    n->next = curr;
    if (prev == NULL_IDX) HEAD_ALLOC = idx;
    else node_get(prev)->next = idx;
}

void alloc_list_remove(uint32_t idx, uint32_t prev) {
    node_t* n = node_get(idx);
    if (prev == NULL_IDX) HEAD_ALLOC = n->next;
    else node_get(prev)->next = n->next;
    n->next = NULL_IDX;
}

uint32_t alloc_list_find(uint64_t ptr, uint32_t* prev_out) {
    uint32_t prev = NULL_IDX;
    uint32_t curr = HEAD_ALLOC;

    while (curr != NULL_IDX) {
        node_t* c = node_get(curr);
        if (c->ptr == ptr) {
            *prev_out = prev;
            return curr;
        }
        prev = curr;
        curr = c->next;
    }

    *prev_out = NULL_IDX;
    return NULL_IDX;
}

