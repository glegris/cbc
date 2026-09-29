/* <inttypes.h> -- C99 7.8. Extends <stdint.h> with intmax_t/uintmax_t,
 * imaxabs()/imaxdiv(), the strtoimax()/strtoumax() conversions, and the
 * PRI-prefixed printf format-specifier macros for each fixed-width type
 * (no SCN-prefixed scanf ones: this compiler has no scanf() at all yet).
 * No wcstoimax()/wcstoumax(): those take a wide-character string, and
 * this compiler has no wide-character support at all (see the main
 * README's "still not provided" list). */
#ifndef _CFLAT_INTTYPES_H
#define _CFLAT_INTTYPES_H

#include "stdint.h"
#include "stdlib.h"  /* for strtol()/strtoul(), used below */

/* intmax_t/uintmax_t are the widest integer type this compiler has --
 * exactly int64_t/uint64_t ("long long"/"unsigned long long"), same
 * reasoning as stdint.h's own int64_t/uint64_t (see its own doc
 * comment). */
typedef long long intmax_t;
typedef unsigned long long uintmax_t;

struct __cflat_imaxdiv_t { intmax_t quot; intmax_t rem; };
typedef struct __cflat_imaxdiv_t imaxdiv_t;

static intmax_t imaxabs(intmax_t x) {
    return (x < 0) ? -x : x;
}

static imaxdiv_t imaxdiv(intmax_t numer, intmax_t denom) {
    imaxdiv_t r;
    r.quot = numer / denom;
    r.rem = numer % denom;
    return r;
}

/* strtoimax()/strtoumax() -- same overflow caveat as stdlib.h's own
 * strtol()/strtoul() (see that header's own comment): no errno=ERANGE
 * clamping, an out-of-range input just silently wraps. Both are simply
 * strtol()/strtoul() under the hood: on the JVM backend "long" is
 * already 8 bytes wide (as wide as intmax_t itself), and on x86 -- where
 * plain "long" is only 4 bytes -- this is the same long-vs-long-long
 * gap already documented for printf's own "%lld" (see stdio.h's own
 * comment on it), not a new one this header introduces. */
static intmax_t strtoimax(char* nptr, char** endptr, int base) {
    return (intmax_t) strtol(nptr, endptr, base);
}

static uintmax_t strtoumax(char* nptr, char** endptr, int base) {
    return (uintmax_t) strtoul(nptr, endptr, base);
}

/* PRI* (printf) format-specifier macros -- only decimal/unsigned/hex,
 * the ones real code actually reaches for; each expands to the same
 * "l"-length-modified conversion stdio.h's own printf() already
 * recognizes (see its own doc comment: "ll" is silently treated the
 * same as a plain "l" there, a pre-existing, documented gap on the x86
 * backend specifically -- not something newly introduced by using
 * these macros instead of spelling "%lld" out by hand). */
#define PRId8  "d"
#define PRIi8  "d"
#define PRIu8  "u"
#define PRIx8  "x"
#define PRIX8  "X"
#define PRIo8  "o"

#define PRId16 "d"
#define PRIi16 "d"
#define PRIu16 "u"
#define PRIx16 "x"
#define PRIX16 "X"
#define PRIo16 "o"

#define PRId32 "d"
#define PRIi32 "d"
#define PRIu32 "u"
#define PRIx32 "x"
#define PRIX32 "X"
#define PRIo32 "o"

#define PRId64 "lld"
#define PRIi64 "lld"
#define PRIu64 "llu"
#define PRIx64 "llx"
#define PRIX64 "llX"
#define PRIo64 "llo"

#define PRIdMAX "lld"
#define PRIiMAX "lld"
#define PRIuMAX "llu"
#define PRIxMAX "llx"
#define PRIXMAX "llX"
#define PRIoMAX "llo"

#define PRIdPTR "ld"
#define PRIiPTR "ld"
#define PRIuPTR "lu"
#define PRIxPTR "lx"
#define PRIXPTR "lX"
#define PRIoPTR "lo"

#endif
