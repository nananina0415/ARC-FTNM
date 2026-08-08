#ifndef _STDIO_H
#define _STDIO_H

#include <stddef.h>
#include <stdarg.h>

#define EOF     (-1)
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2

typedef struct mmix_file FILE;

FILE*         fopen(const char* path, const char* mode);
int           fclose(FILE* stream);
size_t        fread(void* buf, size_t size, size_t count, FILE* stream);
size_t        fwrite(const void* buf, size_t size, size_t count, FILE* stream);
int           fseek(FILE* stream, long offset, int whence);
long          ftell(FILE* stream);
int           feof(FILE* stream);

int           printf(const char* fmt, ...);
int           fprintf(FILE* stream, const char* fmt, ...);
int           sprintf(char* buf, const char* fmt, ...);
int           vsnprintf(char* buf, size_t n, const char* fmt, va_list ap);

#endif
