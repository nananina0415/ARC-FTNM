#include "stdlib.h"
#include "../src/mem.h"
#include "string.h"

void* malloc(size_t size) {
    if (size == 0) size = 1;
    heap_init();

    // 8바이트 정렬
    uint32_t total = ((uint32_t)size + 7u) & ~7u;
    uint32_t prev;
    uint32_t idx = free_list_find_fit(total, &prev);
    if (idx == NULL_IDX) return (void*)0;

    node_t* n = node_get(idx);

    if (n->size > total) {
        uint32_t rem = node_pool_take();
        if (rem == NULL_IDX) return (void*)0;
        node_t* r = node_get(rem);
        r->ptr  = n->ptr + total;
        r->size = n->size - total;
        n->size = total;
        free_list_remove(idx, prev);
        free_list_insert(rem);
    } else {
        free_list_remove(idx, prev);
    }

    alloc_list_insert(idx);
    return (void*)(uintptr_t)n->ptr;
}

void free(void* ptr) {
    if (!ptr) return;
    heap_init();

    uint32_t prev;
    uint32_t idx = alloc_list_find((uint64_t)(uintptr_t)ptr, &prev);
    if (idx == NULL_IDX) return;

    alloc_list_remove(idx, prev);
    free_list_insert(idx);
}

void* realloc(void* ptr, size_t size) {
    if (!ptr)   return malloc(size);
    if (!size)  { free(ptr); return (void*)0; }

    uint32_t prev;
    uint32_t idx = alloc_list_find((uint64_t)(uintptr_t)ptr, &prev);
    if (idx == NULL_IDX) return (void*)0;

    size_t old_size = node_get(idx)->size;
    void*  newptr   = malloc(size);
    if (!newptr) return (void*)0;

    size_t copy = old_size < size ? old_size : size;
    memcpy(newptr, ptr, copy);
    free(ptr);
    return newptr;
}
