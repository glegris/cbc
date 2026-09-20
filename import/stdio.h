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

/* printf/fprintf/sprintf/snprintf and their v...() counterparts: a
 * portable-C format-string engine, adapted from Marco Paland's
 * printf.c (see this project's own top-level MIT license header --
 * that implementation's license text covers this port too), itself
 * possible here only because "..." can be walked one 8-byte slot at a
 * time via va_arg_t (see <stdarg.h>) and reinterpreted by hand
 * (e.g. "long bits = (long)va_next(&ap); double d = *(double*)&bits;"
 * for a double) -- there is no type-directed va_arg() macro to lean on
 * the way a real libc's own vsnprintf() has.
 *
 * printf() is a real function now, not the compile-time intrinsic it
 * used to be (CodeGenerator's own special-casing only applies to a
 * call still left as an UndefinedFunction -- see the JVM backend
 * section of README.md -- so a real definition, like this one, is used
 * normally instead): the format string no longer has to be a compile-
 * time literal, and field width/precision/flags are now interpreted.
 *
 * Not supported: "long long"-sized arguments ("%lld", "ll") -- see
 * this header's own earlier doc comment on why a "long long"-using
 * function body can't safely live here at all; "ll" is silently
 * treated the same as a plain "l" (a 64-bit value is still read
 * correctly whenever "long" itself is already 64-bit, i.e. on the JVM
 * backend, but truncated on x86, where "long" is 32-bit -- the same
 * long-vs-long-long distinction other headers already don't attempt to
 * paper over); "%a"/"%A" (hex float).
 *
 * THIS ENTIRE FAMILY IS JVM-ONLY: "%f"/"%F" needs real "double"
 * arithmetic (__cflat_ftoa below), which the x86 backend rejects
 * outright at code generation time (see its own class doc comment) --
 * and because the format string is a runtime value in general, the
 * "%f" formatter is reachable (see sysdep/x86/CodeGenerator's own
 * computeReachableEntities(), which otherwise prunes an unused
 * function like div()/strtod() out of an x86 build) the moment
 * printf()/fprintf()/sprintf()/snprintf() is called AT ALL, even with
 * a literal, no-"%f" format string -- there is no way to tell from the
 * call graph alone that a given call site's format string will never
 * ask for "%f". So no program calling anything in this family can be
 * compiled for the x86 backend, full stop; see README.md's own
 * <stdio.h> section for the reasoning and the alternatives that were
 * weighed (a from-scratch integer-only "soft float" formatter, or
 * simply dropping "%f" from this shared engine entirely) before
 * accepting this as the tradeoff. */

#define __CFLAT_PRINTF_NTOA_BUFFER_SIZE 32
#define __CFLAT_PRINTF_FTOA_BUFFER_SIZE 32

#define __CFLAT_FLAGS_ZEROPAD   1
#define __CFLAT_FLAGS_LEFT      2
#define __CFLAT_FLAGS_PLUS      4
#define __CFLAT_FLAGS_SPACE     8
#define __CFLAT_FLAGS_HASH      16
#define __CFLAT_FLAGS_UPPERCASE 32
#define __CFLAT_FLAGS_CHAR      64
#define __CFLAT_FLAGS_SHORT     128
#define __CFLAT_FLAGS_LONG      256
#define __CFLAT_FLAGS_PRECISION 512

/* The "output sink" a compiled format string is fed through: a real
 * stream (printf/fprintf), a caller-supplied buffer (sprintf/
 * snprintf), or nowhere at all (a NULL buffer passed to snprintf, to
 * compute the would-be length without writing anything). */
typedef void (FILE*, int, void*, size_t, size_t)* __cflat_out_fct_type;

static void __cflat_out_buffer(FILE* stream, int character, void* buffer, size_t idx, size_t maxlen) {
    (void)stream;
    if (idx < maxlen) {
        ((char*)buffer)[idx] = (char)character;
    }
}

static void __cflat_out_null(FILE* stream, int character, void* buffer, size_t idx, size_t maxlen) {
    (void)stream;
    (void)character;
    (void)buffer;
    (void)idx;
    (void)maxlen;
}

static void __cflat_out_char(FILE* stream, int character, void* buffer, size_t idx, size_t maxlen) {
    (void)buffer;
    (void)idx;
    (void)maxlen;
    /* __cflat_vsnprintf() always finishes with one unconditional
     * out(0, ...) call to place a NUL terminator at the end of a real
     * buffer (see its own closing comment); a stream has no such
     * terminator to write, so that trailing zero byte must be dropped
     * here instead of actually being sent to fputc(), or every
     * printf()/fprintf() call would emit a stray NUL byte. */
    if (character) {
        fputc(character, stream);
    }
}

static unsigned int __cflat_atoi(char** str) {
    unsigned int i = 0;
    while (isdigit((unsigned char)**str)) {
        i = i * 10 + (unsigned int)(**str - '0');
        (*str)++;
    }
    return i;
}

/* Shared tail end of every numeric conversion below: sign, padding
 * (leading zeros or spaces) and the "0x"/"0"/"0b" prefix a "#" flag
 * asks for, then the digits themselves (buf/len are filled least-
 * significant-digit-first by the caller, so this reverses them). */
static size_t __cflat_ntoa_format(FILE* stream, __cflat_out_fct_type out, char* buffer, size_t idx, size_t maxlen,
        char* buf, size_t len, int negative, unsigned int base, unsigned int prec, unsigned int width,
        unsigned int flags) {
    size_t start_idx = idx;
    size_t i;

    if (!(flags & __CFLAT_FLAGS_LEFT)) {
        while (len < prec && len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
            buf[len] = '0';
            len++;
        }
        while ((flags & __CFLAT_FLAGS_ZEROPAD) && len < width && len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
            buf[len] = '0';
            len++;
        }
    }

    if (flags & __CFLAT_FLAGS_HASH) {
        if (!(flags & __CFLAT_FLAGS_PRECISION) && len && (len == prec || len == width)) {
            len--;
            if (len && base == 16) {
                len--;
            }
        }
        if (base == 16 && !(flags & __CFLAT_FLAGS_UPPERCASE) && len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
            buf[len] = 'x';
            len++;
        }
        else if (base == 16 && (flags & __CFLAT_FLAGS_UPPERCASE) && len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
            buf[len] = 'X';
            len++;
        }
        else if (base == 2 && len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
            buf[len] = 'b';
            len++;
        }
        if (len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
            buf[len] = '0';
            len++;
        }
    }

    if (len && len == width && (negative || (flags & __CFLAT_FLAGS_PLUS) || (flags & __CFLAT_FLAGS_SPACE))) {
        len--;
    }
    if (len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE) {
        if (negative) {
            buf[len] = '-';
            len++;
        }
        else if (flags & __CFLAT_FLAGS_PLUS) {
            buf[len] = '+';
            len++;
        }
        else if (flags & __CFLAT_FLAGS_SPACE) {
            buf[len] = ' ';
            len++;
        }
    }

    if (!(flags & __CFLAT_FLAGS_LEFT) && !(flags & __CFLAT_FLAGS_ZEROPAD)) {
        for (i = len; i < width; i++) {
            out(stream, ' ', buffer, idx, maxlen);
            idx++;
        }
    }

    for (i = 0; i < len; i++) {
        out(stream, buf[len - i - 1], buffer, idx, maxlen);
        idx++;
    }

    if (flags & __CFLAT_FLAGS_LEFT) {
        while (idx - start_idx < width) {
            out(stream, ' ', buffer, idx, maxlen);
            idx++;
        }
    }

    return idx;
}

static size_t __cflat_ntoa_long(FILE* stream, __cflat_out_fct_type out, char* buffer, size_t idx, size_t maxlen,
        unsigned long value, int negative, unsigned long base, unsigned int prec, unsigned int width,
        unsigned int flags) {
    char[__CFLAT_PRINTF_NTOA_BUFFER_SIZE] buf;
    size_t len = 0;
    char digit;

    if (!value) {
        flags = flags & ~__CFLAT_FLAGS_HASH;
    }

    if (!(flags & __CFLAT_FLAGS_PRECISION) || value) {
        do {
            digit = (char)(value % base);
            buf[len] = (digit < 10) ? ('0' + digit) : (((flags & __CFLAT_FLAGS_UPPERCASE) ? 'A' : 'a') + digit - 10);
            len++;
            value = value / base;
        } while (value && len < __CFLAT_PRINTF_NTOA_BUFFER_SIZE);
    }

    return __cflat_ntoa_format(stream, out, buffer, idx, maxlen, buf, len, negative, (unsigned int)base, prec,
            width, flags);
}

static double[10] __cflat_pow10 = {
    1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000
};

/* "%f"/"%F": always a fixed decimal-point form (never "%e"-style
 * scientific notation) -- real precision default is 6 fractional
 * digits, matching a real printf(). Extremely large magnitudes (beyond
 * INT_MAX before the decimal point) are refused outright rather than
 * risking a bogus result -- a real printf would fall back to
 * scientific notation instead, which nothing here implements. */
static size_t __cflat_ftoa(FILE* stream, __cflat_out_fct_type out, char* buffer, size_t idx, size_t maxlen,
        double value, unsigned int prec, unsigned int width, unsigned int flags) {
    size_t start_idx = idx;
    char[__CFLAT_PRINTF_FTOA_BUFFER_SIZE] buf;
    size_t len = 0;
    double diff;
    double thres_max = (double)0x7FFFFFFF;
    int negative = 0;
    int whole;
    double tmp;
    unsigned long frac;
    unsigned int count;
    size_t i;

    if (value < 0.0) {
        negative = 1;
        value = 0.0 - value;
    }
    if (value > thres_max) {
        return idx;  /* refuse rather than risk a bogus result -- see this function's own doc comment */
    }

    if (!(flags & __CFLAT_FLAGS_PRECISION)) {
        prec = 6;
    }
    while (len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE && prec > 9) {
        buf[len] = '0';
        len++;
        prec--;
    }

    whole = (int)value;
    tmp = (value - (double)whole) * __cflat_pow10[prec];
    frac = (unsigned long)tmp;
    diff = tmp - (double)frac;

    if (diff > 0.5) {
        frac++;
        if ((double)frac >= __cflat_pow10[prec]) {
            frac = 0;
            whole++;
        }
    }
    else if (diff == 0.5 && (frac == 0 || (frac & 1))) {
        frac++;
    }

    if (prec == 0) {
        diff = value - (double)whole;
        if (diff > 0.5) {
            whole++;
        }
        else if (diff == 0.5 && (whole & 1)) {
            whole++;
        }
    }
    else {
        count = prec;
        while (len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE) {
            count--;
            buf[len] = (char)(48 + (frac % 10));
            len++;
            frac = frac / 10;
            if (frac == 0) break;
        }
        while (len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE && count > 0) {
            buf[len] = '0';
            len++;
            count--;
        }
        if (len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE) {
            buf[len] = '.';
            len++;
        }
    }

    while (len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE) {
        buf[len] = (char)(48 + (whole % 10));
        len++;
        whole = whole / 10;
        if (whole == 0) break;
    }

    if (!(flags & __CFLAT_FLAGS_LEFT) && (flags & __CFLAT_FLAGS_ZEROPAD)) {
        while (len < width && len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE) {
            buf[len] = '0';
            len++;
        }
    }
    if (len == width && (negative || (flags & __CFLAT_FLAGS_PLUS) || (flags & __CFLAT_FLAGS_SPACE))) {
        len--;
    }
    if (len < __CFLAT_PRINTF_FTOA_BUFFER_SIZE) {
        if (negative) {
            buf[len] = '-';
            len++;
        }
        else if (flags & __CFLAT_FLAGS_PLUS) {
            buf[len] = '+';
            len++;
        }
        else if (flags & __CFLAT_FLAGS_SPACE) {
            buf[len] = ' ';
            len++;
        }
    }

    if (!(flags & __CFLAT_FLAGS_LEFT) && !(flags & __CFLAT_FLAGS_ZEROPAD)) {
        for (i = len; i < width; i++) {
            out(stream, ' ', buffer, idx, maxlen);
            idx++;
        }
    }
    for (i = 0; i < len; i++) {
        out(stream, buf[len - i - 1], buffer, idx, maxlen);
        idx++;
    }
    if (flags & __CFLAT_FLAGS_LEFT) {
        while (idx - start_idx < width) {
            out(stream, ' ', buffer, idx, maxlen);
            idx++;
        }
    }

    return idx;
}

static int __cflat_vsnprintf(FILE* stream, __cflat_out_fct_type out, char* buffer, size_t maxlen, char* format,
        va_list va) {
    unsigned int flags, width, precision, n;
    size_t idx = 0;

    if (!buffer) {
        out = __cflat_out_null;
    }

    while (*format) {
        if (*format != '%') {
            out(stream, *format, buffer, idx, maxlen);
            idx++;
            format++;
            continue;
        }
        format++;

        flags = 0;
        do {
            switch (*format) {
            case '0': flags = flags | __CFLAT_FLAGS_ZEROPAD; format++; n = 1; break;
            case '-': flags = flags | __CFLAT_FLAGS_LEFT;    format++; n = 1; break;
            case '+': flags = flags | __CFLAT_FLAGS_PLUS;    format++; n = 1; break;
            case ' ': flags = flags | __CFLAT_FLAGS_SPACE;   format++; n = 1; break;
            case '#': flags = flags | __CFLAT_FLAGS_HASH;    format++; n = 1; break;
            default:  n = 0; break;
            }
        } while (n);

        width = 0;
        if (isdigit((unsigned char)*format)) {
            width = __cflat_atoi(&format);
        }
        else if (*format == '*') {
            int w = (int)va_next(&va);
            if (w < 0) {
                flags = flags | __CFLAT_FLAGS_LEFT;
                width = (unsigned int)(-w);
            }
            else {
                width = (unsigned int)w;
            }
            format++;
        }

        precision = 0;
        if (*format == '.') {
            flags = flags | __CFLAT_FLAGS_PRECISION;
            format++;
            if (isdigit((unsigned char)*format)) {
                precision = __cflat_atoi(&format);
            }
            else if (*format == '*') {
                int prec = (int)va_next(&va);
                precision = (prec > 0) ? (unsigned int)prec : 0;
                format++;
            }
        }

        switch (*format) {
        case 'l':
            flags = flags | __CFLAT_FLAGS_LONG;
            format++;
            if (*format == 'l') {
                format++;  /* "ll" -- see this header's own top doc comment */
            }
            break;
        case 'h':
            flags = flags | __CFLAT_FLAGS_SHORT;
            format++;
            if (*format == 'h') {
                flags = flags | __CFLAT_FLAGS_CHAR;
                format++;
            }
            break;
        case 't': case 'j': case 'z':
            flags = flags | __CFLAT_FLAGS_LONG;  /* all just "long" here -- see <stdint.h> */
            format++;
            break;
        default:
            break;
        }

        switch (*format) {
        case 'd': case 'i': case 'u': case 'x': case 'X': case 'o': case 'b': {
            unsigned int base;
            int is_signed = (*format == 'i' || *format == 'd');

            if (*format == 'x' || *format == 'X') base = 16;
            else if (*format == 'o') base = 8;
            else if (*format == 'b') base = 2;
            else {
                base = 10;
                flags = flags & ~__CFLAT_FLAGS_HASH;
            }
            if (*format == 'X') flags = flags | __CFLAT_FLAGS_UPPERCASE;
            if (!is_signed) flags = flags & ~(__CFLAT_FLAGS_PLUS | __CFLAT_FLAGS_SPACE);
            if (flags & __CFLAT_FLAGS_PRECISION) flags = flags & ~__CFLAT_FLAGS_ZEROPAD;

            if (is_signed) {
                long value;
                int negative;
                if (flags & __CFLAT_FLAGS_LONG) {
                    value = (long)va_next(&va);
                }
                else if (flags & __CFLAT_FLAGS_CHAR) {
                    value = (long)(char)(int)va_next(&va);
                }
                else if (flags & __CFLAT_FLAGS_SHORT) {
                    value = (long)(short)(int)va_next(&va);
                }
                else {
                    value = (long)(int)va_next(&va);
                }
                negative = (value < 0);
                idx = __cflat_ntoa_long(stream, out, buffer, idx, maxlen,
                        (unsigned long)(negative ? (0L - value) : value), negative, base, precision, width, flags);
            }
            else {
                unsigned long uvalue;
                if (flags & __CFLAT_FLAGS_LONG) {
                    uvalue = (unsigned long)va_next(&va);
                }
                else if (flags & __CFLAT_FLAGS_CHAR) {
                    uvalue = (unsigned long)(unsigned char)(int)va_next(&va);
                }
                else if (flags & __CFLAT_FLAGS_SHORT) {
                    uvalue = (unsigned long)(unsigned short)(int)va_next(&va);
                }
                else {
                    uvalue = (unsigned long)(unsigned int)(int)va_next(&va);
                }
                idx = __cflat_ntoa_long(stream, out, buffer, idx, maxlen, uvalue, 0, base, precision, width, flags);
            }
            format++;
            break;
        }
        case 'f': case 'F': {
            long bits = (long)va_next(&va);
            idx = __cflat_ftoa(stream, out, buffer, idx, maxlen, *(double*)&bits, precision, width, flags);
            format++;
            break;
        }
        case 'c': {
            unsigned int pad = (width > 1) ? (width - 1) : 0;
            unsigned int i;
            if (!(flags & __CFLAT_FLAGS_LEFT)) {
                for (i = 0; i < pad; i++) { out(stream, ' ', buffer, idx, maxlen); idx++; }
            }
            out(stream, (char)(int)va_next(&va), buffer, idx, maxlen);
            idx++;
            if (flags & __CFLAT_FLAGS_LEFT) {
                for (i = 0; i < pad; i++) { out(stream, ' ', buffer, idx, maxlen); idx++; }
            }
            format++;
            break;
        }
        case 's': {
            char* p = (char*)va_next(&va);
            unsigned int l = (unsigned int)strlen(p);
            unsigned int pad;
            unsigned int i;
            if ((flags & __CFLAT_FLAGS_PRECISION) && l > precision) {
                l = precision;
            }
            pad = (width > l) ? (width - l) : 0;
            if (!(flags & __CFLAT_FLAGS_LEFT)) {
                for (i = 0; i < pad; i++) { out(stream, ' ', buffer, idx, maxlen); idx++; }
            }
            for (i = 0; i < l; i++) {
                out(stream, p[i], buffer, idx, maxlen);
                idx++;
            }
            if (flags & __CFLAT_FLAGS_LEFT) {
                for (i = 0; i < pad; i++) { out(stream, ' ', buffer, idx, maxlen); idx++; }
            }
            format++;
            break;
        }
        case 'p': {
            /* Lowercase "0x"-prefixed hex with no forced width/zero-
             * padding, matching common libc convention (e.g. glibc) --
             * unlike Paland's own printf.c, which pads %p to the full
             * pointer width in uppercase with no "0x" prefix at all. */
            unsigned long ptrval = (unsigned long)va_next(&va);
            flags = flags | __CFLAT_FLAGS_HASH;
            idx = __cflat_ntoa_long(stream, out, buffer, idx, maxlen, ptrval, 0, 16, precision, width, flags);
            format++;
            break;
        }
        case '%':
            out(stream, '%', buffer, idx, maxlen);
            idx++;
            format++;
            break;
        default:
            out(stream, *format, buffer, idx, maxlen);
            idx++;
            format++;
            break;
        }
    }

    out(stream, (int)0, buffer, (idx < maxlen) ? idx : maxlen - 1, maxlen);
    return (int)idx;
}

static int vfprintf(FILE* stream, char* format, va_list va) {
    char[1] buf;
    return __cflat_vsnprintf(stream, __cflat_out_char, buf, (size_t)-1L, format, va);
}

static int vprintf(char* format, va_list va) {
    return vfprintf(stdout, format, va);
}

static int vsprintf(char* buffer, char* format, va_list va) {
    return __cflat_vsnprintf(stdout, __cflat_out_buffer, buffer, (size_t)-1L, format, va);
}

static int vsnprintf(char* buffer, size_t count, char* format, va_list va) {
    return __cflat_vsnprintf(stdout, __cflat_out_buffer, buffer, count, format, va);
}

static int fprintf(FILE* stream, char* format, ...) {
    va_list va;
    int ret;
    va = va_init(&format);
    ret = vfprintf(stream, format, va);
    return ret;
}

static int printf(char* format, ...) {
    va_list va;
    int ret;
    va = va_init(&format);
    ret = vfprintf(stdout, format, va);
    return ret;
}

static int sprintf(char* buffer, char* format, ...) {
    va_list va;
    int ret;
    va = va_init(&format);
    ret = vsprintf(buffer, format, va);
    return ret;
}

static int snprintf(char* buffer, size_t count, char* format, ...) {
    va_list va;
    int ret;
    va = va_init(&format);
    ret = vsnprintf(buffer, count, format, va);
    return ret;
}

#endif
