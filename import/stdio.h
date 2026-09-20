/* <stdio.h> -- C99 7.19 */
#ifndef _CFLAT_STDIO_H
#define _CFLAT_STDIO_H

#include "stddef.h"  /* for NULL and size_t */
#include "stdarg.h"
#include "stdlib.h"  /* for malloc()/free(), used by fopen()/fclose() below */
#include "string.h"  /* for strlen(), used by fputs() below */

#define EOF (-1)

/* Real file I/O: fopen()/fclose()/fread()/fwrite()/fgets()/fputc()/
 * fgetc()/feof()/ftell()/fseek()/fflush() and friends below (including
 * putchar()/puts(), now real functions rather than the compile-time
 * intrinsics they'd otherwise be -- see the JVM backend section of
 * README.md: CodeGenerator only special-cases a call to one of those
 * two names, or printf, when it's still an UndefinedFunction, i.e. only
 * ever *declared*; a real definition, like the ones below, is used
 * normally instead), all portable C wrapping the small set of native
 * primitives StandardRuntime implements for the JVM backend
 * (mir_sysio_open() and friends -- see that file's own doc comment);
 * the real libc's own equivalents back them on x86. FILE itself is just
 * an fd wrapper, "static" for the same reason every function below is
 * (see <assert.h>'s own doc comment): two different .c files both
 * including this header must not collide at link time on x86, where
 * each is a separate translation unit. */
struct __cflat_FILE { int fd; int iseof; };
typedef struct __cflat_FILE FILE;

static struct __cflat_FILE __cflat_stdin_obj = { 0, 0 };
static struct __cflat_FILE __cflat_stdout_obj = { 1, 0 };
static struct __cflat_FILE __cflat_stderr_obj = { 2, 0 };

static FILE* stdin = &__cflat_stdin_obj;
static FILE* stdout = &__cflat_stdout_obj;
static FILE* stderr = &__cflat_stderr_obj;

extern int mir_sysio_open(char* path, char* mode);
extern int mir_sysio_close(int fd);
extern long mir_sysio_read(int fd, void* buf, unsigned long count);
extern long mir_sysio_write(int fd, void* buf, unsigned long count);
extern long mir_sysio_seek(int fd, long offset, int whence);
extern long mir_sysio_tell(int fd);
extern int mir_sysio_feof(int fd);

static FILE* fopen(char* path, char* mode) {
    int fd = mir_sysio_open(path, mode);
    if (fd < 0) return NULL;
    FILE* stream = (FILE*)malloc(sizeof(FILE));
    if (stream == NULL) {
        mir_sysio_close(fd);
        return NULL;
    }
    stream->fd = fd;
    stream->iseof = 0;
    return stream;
}

static FILE* freopen(char* path, char* mode, FILE* stream) {
    if (stream == NULL) return fopen(path, mode);
    int fd = mir_sysio_open(path, mode);
    if (fd < 0) return NULL;
    mir_sysio_close(stream->fd);
    stream->fd = fd;
    stream->iseof = 0;
    return stream;
}

static int fclose(FILE* stream) {
    if (stream == NULL) return EOF;
    int rc = mir_sysio_close(stream->fd);
    free(stream);
    return (rc < 0) ? EOF : 0;
}

static size_t fread(void* buf, size_t size, size_t nmemb, FILE* stream) {
    if (stream == NULL || buf == NULL) return 0;
    long want = (long)(size * nmemb);
    long got = mir_sysio_read(stream->fd, buf, want);
    if (got <= 0) {
        stream->iseof = 1;
        return 0;
    }
    return (size_t)(got / (long)size);
}

static size_t fwrite(void* buf, size_t size, size_t nmemb, FILE* stream) {
    if (stream == NULL || buf == NULL) return 0;
    long want = (long)(size * nmemb);
    long put = mir_sysio_write(stream->fd, buf, want);
    if (put < 0) return 0;
    return (size_t)(put / (long)size);
}

static int feof(FILE* stream) {
    if (stream == NULL) return 0;
    return stream->iseof || mir_sysio_feof(stream->fd);
}

static int ferror(FILE* stream) {
    (void)stream;
    return 0;  /* no errno to report a real error through -- see <errno.h> */
}

static void clearerr(FILE* stream) {
    if (stream != NULL) stream->iseof = 0;
}

static int fileno(FILE* stream) {
    return (stream != NULL) ? stream->fd : -1;
}

static long ftell(FILE* stream) {
    if (stream == NULL) return -1L;
    return mir_sysio_tell(stream->fd);
}

static int fseek(FILE* stream, long offset, int whence) {
    if (stream == NULL) return -1;
    long rc = mir_sysio_seek(stream->fd, offset, whence);
    if (rc < 0) return -1;
    stream->iseof = 0;
    return 0;
}

static int fflush(FILE* stream) {
    /* Every write above already goes straight through mir_sysio_write()
     * (no buffering layer sits in front of it), so there's nothing left
     * to flush. */
    (void)stream;
    return 0;
}

static int fputc(int c, FILE* stream) {
    if (stream == NULL) return EOF;
    unsigned char ch = (unsigned char)c;
    long rc = mir_sysio_write(stream->fd, &ch, 1);
    return (rc < 0) ? EOF : (int)ch;
}

static int putc(int c, FILE* stream) {
    return fputc(c, stream);
}

static int putchar(int c) {
    return fputc(c, stdout);
}

static int fputs(char* s, FILE* stream) {
    if (stream == NULL || s == NULL) return EOF;
    long len = (long)strlen(s);
    long rc = mir_sysio_write(stream->fd, s, len);
    return (rc < 0) ? EOF : (int)rc;
}

static int puts(char* s) {
    if (fputs(s, stdout) == EOF) return EOF;
    return fputc('\n', stdout);
}

static int fgetc(FILE* stream) {
    if (stream == NULL) return EOF;
    unsigned char ch = 0;
    long rc = mir_sysio_read(stream->fd, &ch, 1);
    if (rc <= 0) {
        stream->iseof = 1;
        return EOF;
    }
    return (int)ch;
}

static int getc(FILE* stream) {
    return fgetc(stream);
}

static int getchar(void) {
    return fgetc(stdin);
}

static int ungetc(int c, FILE* stream) {
    /* No pushback buffer: real callers only ever unget the byte they
     * just read to peek ahead by one, which this can't support without
     * one -- always fails, matching real ungetc()'s own "on failure"
     * return. */
    (void)c;
    (void)stream;
    return EOF;
}

static char* fgets(char* s, int n, FILE* stream) {
    if (stream == NULL || s == NULL || n <= 1) return NULL;
    int i = 0;
    while (i < n - 1) {
        int c = fgetc(stream);
        if (c == EOF) break;
        s[i++] = (char)c;
        if (c == '\n') break;
    }
    if (i == 0) return NULL;
    s[i] = '\0';
    return s;
}

static char* gets(char* s) {
    /* No bound on input length -- real gets() is exactly this unsafe,
     * which is why C11 removed it; kept only for old code that still
     * calls it. */
    int i = 0;
    int c;
    while ((c = fgetc(stdin)) != EOF && c != '\n') {
        s[i++] = (char)c;
    }
    if (i == 0 && c == EOF) return NULL;
    s[i] = '\0';
    return s;
}

static void perror(char* s) {
    if (s != NULL && *s != '\0') {
        fputs(s, stderr);
        fputs(": ", stderr);
    }
    fputs("error\n", stderr);  /* no errno to report a real message for -- see <errno.h> */
}

/* printf/fprintf/sprintf/snprintf and their v...() counterparts:
 * printf is the one remaining compile-time intrinsic (still an
 * UndefinedFunction, so CodeGenerator's own special-casing still
 * applies -- see the JVM backend section of README.md, including its
 * literal-format-string requirement); the rest are declared but NOT
 * implemented here, since there's no portable-C way to write a real
 * format-string parser/dispatcher in cflat itself the way the rest of
 * this header's functions are: "..." can only be walked with
 * va_arg_t's own fixed-width slots (see <stdarg.h>), never type-directed
 * by a runtime-inspected format string the way a real vsnprintf() needs
 * to be. Calling any of these four (or their v...() counterparts) on
 * the JVM backend currently throws NotImplementedException at runtime
 * (a NativeRuntime stub); they work as normal on the real-libc-linked
 * x86 backend. */
extern int printf(char *fmt, ...);
extern int fprintf(FILE* stream, char* fmt, ...);
extern int sprintf(char* buf, char* fmt, ...);
extern int snprintf(char* buf, size_t size, char* fmt, ...);
extern int vprintf(char* fmt, va_list ap);
extern int vfprintf(FILE* s, char* fmt, va_list ap);
extern int vsprintf(char *buf, char* fmt, va_list ap);
extern int vsnprintf(char *buf, size_t size, char* fmt, va_list ap);

#endif
