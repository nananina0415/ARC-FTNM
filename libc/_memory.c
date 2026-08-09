#include "_memory.h"

static int heap_initialized = 0;

void heap_init(void) {
    if (heap_initialized) return;
    heap_initialized = 1;

    HEAD_FREE  = 1;
    HEAD_ALLOC = NULL_IDX;
    HEAD_POOL  = 2;

    node_t* n1 = node_get(1);
    n1->ptr  = HEAP_BASE;
    n1->size = HEAP_SIZE;
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

