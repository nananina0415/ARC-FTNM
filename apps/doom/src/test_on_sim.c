#define DOOM_IMPLEMENTATION
#include "PureDOOM.h"
#include <stdio.h>
#include <stdlib.h>

static int g_exit = 0;
static int g_sec  = 0;

static void cb_print(const char* s) { printf("%s", s); }

static void* cb_malloc(int size) { return malloc((size_t)size); }
static void  cb_free(void* p)    { free(p); }

static void* cb_open (const char* path, const char* mode) { return (void*)fopen(path, mode); }
static void  cb_close(void* h)                            { fclose((FILE*)h); }
static int   cb_read (void* h, void* buf, int n)          { return (int)fread(buf, 1, (size_t)n, (FILE*)h); }
static int   cb_write(void* h, const void* buf, int n)    { return (int)fwrite(buf, 1, (size_t)n, (FILE*)h); }
static int   cb_seek (void* h, int offset, doom_seek_t o) { return fseek((FILE*)h, (long)offset, (int)o); }
static int   cb_tell (void* h)                            { return (int)ftell((FILE*)h); }
static int   cb_eof  (void* h)                            { return feof((FILE*)h); }

static void  cb_gettime(int* sec, int* usec) { *sec = g_sec++; *usec = 0; }
static void  cb_exit(int code)               { (void)code; g_exit = 1; }
static char* cb_getenv(const char* var) {
    /* HOME=. → basedefault="./.doomrc" → 없으면 조용히 스킵, g_exit 세팅 방지 */
    if (var[0]=='H'&&var[1]=='O'&&var[2]=='M'&&var[3]=='E'&&var[4]=='\0') return ".";
    return (void*)0;
}

static void save_ppm(const unsigned char* rgb) {
    FILE* f = fopen("frame.ppm", "w");
    if (!f) { printf("frame.ppm open failed\n"); return; }
    fprintf(f, "P6\n320 200\n255\n");
    fwrite(rgb, 1, 320 * 200 * 3, f);
    fclose(f);
    printf("frame.ppm saved\n");
}

int main(void) {
    doom_set_print(cb_print);
    doom_set_malloc(cb_malloc, cb_free);
    doom_set_file_io(cb_open, cb_close, cb_read, cb_write,
                     cb_seek, cb_tell, cb_eof);
    doom_set_gettime(cb_gettime);
    doom_set_exit(cb_exit);
    doom_set_getenv(cb_getenv);

    char* argv[] = { "doom", "-iwad", "../PureDOOM/doom1.wad" };
    doom_init(3, argv,
              DOOM_FLAG_HIDE_SOUND_OPTIONS | DOOM_FLAG_HIDE_MUSIC_OPTIONS);

    for (int i = 0; i < 50 && !g_exit; i++)
        doom_force_update();

    save_ppm(doom_get_framebuffer(3));
    return 0;
}
