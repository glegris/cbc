/* <string.h> -- C99 7.21 */
#ifndef _CFLAT_STRING_H
#define _CFLAT_STRING_H

#include "stddef.h"  /* for size_t */
#include "stdlib.h"  /* for malloc(), used by strdup()/strndup() below */

/* Implemented in StandardRuntime.java on the JVM backend, and by the
 * real libc on x86 -- see that file's own doc comment for why these
 * ones are hand-implemented there rather than written here in portable
 * C like the rest of this header below: mostly historical (they were
 * the first batch, before this header supported real function bodies
 * at all), not a hard requirement. */
extern char* strcat(char* dest, char* src);
extern char* strncat(char* dest, char* src, size_t len);
extern char* strchr(char* str, int c);
extern int strcmp(char* str1, char* str2);
extern int strncmp(char* str1, char* str2, size_t len);
extern char* strcpy(char* dest, char* src);
extern char* strncpy(char* dest, char* src, size_t len);
extern size_t strlen(char* str);
extern void* memcpy(void* dest, void* src, size_t len);
extern void* memmove(void* dest, void* src, size_t len);
extern void* memset(void* dest, int c, size_t len);
extern int memcmp(void* a, void* b, size_t len);

/* The rest of <string.h>: none of these need anything host-specific --
 * "static" so that two different .c files both including this header
 * don't collide (see assert.h's own doc comment for exactly why). This
 * runs identically on both backends, with no JVM-specific
 * implementation to keep in sync. */

static void* memchr(void* s, int c, size_t n) {
    unsigned char* p = (unsigned char*)s;
    unsigned char uc = (unsigned char)c;
    while (n--) {
        if (*p == uc) return (void*)p;
        p++;
    }
    return NULL;
}

static void* memccpy(void* dest, void* src, int c, size_t n) {
    unsigned char* d = (unsigned char*)dest;
    unsigned char* s = (unsigned char*)src;
    unsigned char uc = (unsigned char)c;
    size_t i;
    for (i = 0; i < n; i++) {
        d[i] = s[i];
        if (s[i] == uc) return (void*)(d + i + 1);
    }
    return NULL;
}

static char* strrchr(char* str, int c) {
    char* last = NULL;
    char target = (char)c;
    while (1) {
        if (*str == target) last = str;
        if (*str == '\0') return last;
        str++;
    }
}

static size_t strspn(char* str, char* accept) {
    size_t n = 0;
    while (*str != '\0' && strchr(accept, *str) != NULL) {
        str++;
        n++;
    }
    return n;
}

static size_t strcspn(char* str, char* reject) {
    size_t n = 0;
    while (*str != '\0' && strchr(reject, *str) == NULL) {
        str++;
        n++;
    }
    return n;
}

static char* strpbrk(char* str, char* accept) {
    while (*str != '\0') {
        if (strchr(accept, *str) != NULL) return str;
        str++;
    }
    return NULL;
}

static char* strstr(char* str, char* pattern) {
    if (*pattern == '\0') return str;
    while (*str != '\0') {
        char* s = str;
        char* p = pattern;
        while (*p != '\0' && *s == *p) {
            s++;
            p++;
        }
        if (*p == '\0') return str;
        str++;
    }
    return NULL;
}

static size_t strnlen(char* str, size_t maxlen) {
    size_t n = 0;
    while (n != maxlen && str[n] != '\0') n++;
    return n;
}

static int strcoll(char* str1, char* str2) {
    return strcmp(str1, str2);
}

static size_t strxfrm(char* dest, char* src, size_t n) {
    size_t len = strlen(src);
    if (len < n) strncpy(dest, src, n);
    return len;
}

/* strtok() keeps its scan position in a "static" local, exactly like a
 * real libc's thread-unsafe (but standard-mandated) implementation --
 * pass a non-NULL "str" to start tokenizing a new string, NULL to
 * continue the previous one. */
static char* strtok(char* str, char* delim) {
    static char* saved = NULL;
    char* start;
    if (str != NULL) saved = str;
    if (saved == NULL) return NULL;
    saved += strspn(saved, delim);
    if (*saved == '\0') {
        saved = NULL;
        return NULL;
    }
    start = saved;
    saved += strcspn(saved, delim);
    if (*saved != '\0') {
        *saved = '\0';
        saved++;
    }
    else {
        saved = NULL;
    }
    return start;
}

static char* strdup(char* str) {
    size_t len = strlen(str) + 1;
    char* copy = (char*)malloc(len);
    if (copy != NULL) memcpy(copy, str, len);
    return copy;
}

static char* strndup(char* str, size_t n) {
    size_t len = strnlen(str, n);
    char* copy = (char*)malloc(len + 1);
    if (copy != NULL) {
        memcpy(copy, str, len);
        copy[len] = '\0';
    }
    return copy;
}

/* This compiler's own errno.h deliberately doesn't define EDOM/ERANGE/
 * EILSEQ (nothing here ever sets them -- see its own doc comment), so
 * unlike a real libc's strerror(), this can't map a specific errno
 * value to a specific message; it only distinguishes "no error" from
 * everything else. */
static char* strerror(int errnum) {
    return (errnum == 0) ? "No error" : "Unknown error";
}

static char* strerror_r(int errnum, char* buf, size_t len) {
    strncpy(buf, strerror(errnum), len);
    return buf;
}

#endif
