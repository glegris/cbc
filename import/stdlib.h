/* <stdlib.h> -- C99 7.20 */
#ifndef _CFLAT_STDLIB_H
#define _CFLAT_STDLIB_H

#include "stddef.h"  /* for size_t */
#include "ctype.h"   /* for isspace()/isdigit(), used by strtoul()/strtod() below */

extern void exit(int status);
extern int system(char* command);

/* Dynamic memory, and the numeric conversions below -- already
 * implemented in StandardRuntime.java on the JVM backend
 * (net.loveruby.cflat.sysdep.jvm.runtime; malloc/calloc/realloc/free are
 * a real first-fit free-list allocator there, not a stub), and by the
 * real libc on x86. */
extern void* calloc(size_t nmemb, size_t size);
extern void* malloc(size_t size);
extern void free(void* ptr);
extern void* realloc(void* ptr, size_t size);
extern int abs(int x);
extern long labs(long x);
extern int atoi(char* s);
extern long atol(char* s);
extern double atof(char* s);

/* long long-based functions -- llabs(), atoll(), strtoll(), strtoull()
 * -- are deliberately left undeclared here: the x86 backend rejects
 * "long long" outright (see README's own note on it), so a real body
 * using it in this header -- included everywhere, unconditionally --
 * would break every x86 build that includes <stdlib.h>, not just a
 * program that actually calls one of them. There's no per-backend
 * conditional-compilation mechanism here to guard it with either (see
 * <limits.h>'s own comment on why one was deliberately never added).
 *
 * The rest of this header: none of these need anything host-specific --
 * "static" so that two different .c files both including this header
 * don't collide (see assert.h's own doc comment for exactly why). This
 * runs identically on both backends, with no JVM-specific
 * implementation to keep in sync. */

struct __cflat_div_t { int quot; int rem; };
typedef struct __cflat_div_t div_t;

struct __cflat_ldiv_t { long quot; long rem; };
typedef struct __cflat_ldiv_t ldiv_t;

static div_t div(int numer, int denom) {
    div_t r;
    r.quot = numer / denom;
    r.rem = numer % denom;
    return r;
}

static ldiv_t ldiv(long numer, long denom) {
    ldiv_t r;
    r.quot = numer / denom;
    r.rem = numer % denom;
    return r;
}

/* Real strtoul()/strtol() overflow behavior (clamp to
 * ULONG_MAX/LONG_MIN/LONG_MAX and set errno=ERANGE) isn't implemented:
 * this compiler's own <errno.h> deliberately never sets errno itself
 * (see its own doc comment), so there is nowhere meaningful to report
 * that from anyway -- an out-of-range input just silently wraps. */
static unsigned long strtoul(char* nptr, char** endptr, int base) {
    char* p = nptr;
    int neg = 0;
    unsigned long result = 0;
    int any = 0;
    int digit;
    while (isspace((unsigned char)*p)) p++;
    if (*p == '+') p++;
    else if (*p == '-') { neg = 1; p++; }
    if ((base == 0 || base == 16) && p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) {
        base = 16;
        p += 2;
    }
    else if (base == 0) {
        base = (p[0] == '0') ? 8 : 10;
    }
    while (1) {
        int c = (unsigned char)*p;
        if (c >= '0' && c <= '9') digit = c - '0';
        else if (c >= 'a' && c <= 'z') digit = c - 'a' + 10;
        else if (c >= 'A' && c <= 'Z') digit = c - 'A' + 10;
        else break;
        if (digit >= base) break;
        result = result * base + digit;
        p++;
        any = 1;
    }
    if (endptr != NULL) *endptr = any ? p : nptr;
    return neg ? (0UL - result) : result;
}

static long strtol(char* nptr, char** endptr, int base) {
    /* strtoul() already parses a leading "-" itself and negates via
     * unsigned wraparound (see its own "return neg ? (0UL - result) :
     * result;") -- reinterpreting that bit pattern as signed "long"
     * below is the correct negative value already, with no second
     * negation needed (applying one here on top would cancel it back
     * out to positive). */
    return (long)strtoul(nptr, endptr, base);
}

static double strtod(char* nptr, char** endptr) {
    char* p = nptr;
    double sign = 1.0;
    double x = 0.0;
    while (isspace((unsigned char)*p)) p++;
    if (*p == '-') { sign = -1.0; p++; }
    else if (*p == '+') p++;
    while (isdigit((unsigned char)*p)) {
        x = x * 10.0 + (*p - '0');
        p++;
    }
    if (*p == '.') {
        double frac = 0.0, scale = 1.0;
        p++;
        while (isdigit((unsigned char)*p)) {
            frac = frac * 10.0 + (*p - '0');
            scale = scale * 10.0;
            p++;
        }
        x = x + frac / scale;
    }
    if (*p == 'e' || *p == 'E') {
        char* estart = p;
        int esign = 1;
        p++;
        if (*p == '-') { esign = -1; p++; }
        else if (*p == '+') p++;
        if (isdigit((unsigned char)*p)) {
            int exp = 0;
            double scale = 1.0;
            int i;
            while (isdigit((unsigned char)*p)) {
                exp = exp * 10 + (*p - '0');
                p++;
            }
            for (i = 0; i < exp; i++) scale = scale * 10.0;
            x = (esign > 0) ? x * scale : x / scale;
        }
        else {
            p = estart;  /* "1e" with no digits after -- not part of the number */
        }
    }
    if (endptr != NULL) *endptr = p;
    return sign * x;
}

/* Real rand()/srand() implementation-defined sequence isn't matched
 * (nothing requires it to be): a plain linear congruential generator,
 * matching glibc's own minimal RAND_MAX guarantee. */
#define RAND_MAX 32767

static unsigned long __cflat_rand_seed = 1;

static int rand(void) {
    __cflat_rand_seed = __cflat_rand_seed * 1103515245UL + 12345UL;
    return (int)((__cflat_rand_seed >> 16) & RAND_MAX);
}

static void srand(unsigned int seed) {
    __cflat_rand_seed = seed;
}

static void* bsearch(void* key, void* base, size_t nmemb, size_t size,
        int (void*, void*)* compar) {
    char* arr = (char*)base;
    while (nmemb > 0) {
        size_t mid = nmemb / 2;
        char* candidate = arr + mid * size;
        int res = compar(candidate, key);
        if (res == 0) return candidate;
        if (res < 0) {
            arr = candidate + size;
            nmemb = nmemb - mid - 1;
        }
        else {
            nmemb = mid;
        }
    }
    return NULL;
}

static void __cflat_swap_bytes(char* a, char* b, size_t size) {
    size_t i;
    for (i = 0; i < size; i++) {
        char t = a[i];
        a[i] = b[i];
        b[i] = t;
    }
}

/* Plain recursive quicksort (Lomuto partition, last element as pivot):
 * O(n log n) on average, same as any other qsort -- worst case O(n^2)
 * on already-sorted input, which a real production qsort avoids with a
 * smarter pivot choice; not worth the extra complexity here. */
static void qsort(void* base, size_t nmemb, size_t size,
        int (void*, void*)* compar) {
    char* arr = (char*)base;
    if (nmemb < 2) return;
    char* pivot = arr + (nmemb - 1) * size;
    size_t i = 0;
    size_t j;
    for (j = 0; j < nmemb - 1; j++) {
        if (compar(arr + j * size, pivot) < 0) {
            __cflat_swap_bytes(arr + i * size, arr + j * size, size);
            i++;
        }
    }
    __cflat_swap_bytes(arr + i * size, pivot, size);
    qsort(arr, i, size, compar);
    qsort(arr + (i + 1) * size, nmemb - i - 1, size, compar);
}

/* Multibyte conversions: this compiler has no wide-character/locale
 * support at all (see README's "still not provided" list), so these
 * only ever handle the trivial single-byte case real callers actually
 * exercise without a real multibyte locale active. */
static int mblen(char* s, size_t n) {
    if (s == NULL) return 0;
    return (n >= 1) ? 1 : -1;
}

static int mbtowc(int* pwc, char* s, size_t n) {
    if (s == NULL) return 0;
    if (n < 1) return -1;
    if (pwc != NULL) *pwc = (unsigned char)*s;
    return 1;
}

static int wctomb(char* s, int wchar) {
    if (s == NULL) return 0;
    *s = (char)wchar;
    return 1;
}

#endif
