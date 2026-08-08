#ifndef _STDLIB_H
#define _STDLIB_H

#include <stddef.h>

void* malloc(size_t size);
void  free(void* ptr);
void* realloc(void* ptr, size_t size);

void  exit(int status);
char* getenv(const char* name);

int   abs(int n);
long  labs(long n);

#endif
