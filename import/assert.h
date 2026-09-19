/* <assert.h> -- C99 7.2. Deliberately has no include guard around the
 * assert() macro itself (only the one-time preamble below is guarded):
 * real C99 requires "#include <assert.h>" to redefine assert() every
 * time it's included, based on whatever NDEBUG is (or isn't) defined
 * to *at that point* -- e.g. "#define NDEBUG" then "#include
 * <assert.h>" turns assertions off from there on, even partway through
 * a file. A one-time guard around that part would silently break
 * exactly that use.
 *
 * __cflat_assert_fail() is "static" so that two different .cb files
 * both including this header don't collide -- on x86 each is its own
 * translation unit, linked together afterward, so a plain (non-static)
 * definition repeated in each one's assert.h copy would be a duplicate-
 * symbol error; on the JVM backend, where the whole program (headers
 * included) is one file/class anyway, "static" just means "private",
 * which is all this ever needed to be. It uses only printf() and
 * exit() -- already declared/implemented on both backends -- rather
 * than a dedicated runtime primitive, so this header alone is enough.
 * It exists only because this compiler has no comma operator (real
 * assert() is usually "((expr) || (fprintf(...), abort(), 0))"; without
 * one, the failure path needs to be a single call already returning a
 * value, not a sequence of them.
 */
#ifndef _CFLAT_ASSERT_H
#define _CFLAT_ASSERT_H
#include "stdio.h"
#include "stdlib.h"

static int __cflat_assert_fail(char* expr, char* file, int line) {
    printf("Assertion failed: %s, file %s, line %d\n", expr, file, line);
    exit(1);
    return 0;  /* unreachable -- exit() never returns -- but assert()
                * still needs an int value out of this call. */
}
#endif

#undef assert

#ifdef NDEBUG
#define assert(expr) ((void)0)
#else
#define assert(expr) ((void)((expr) || __cflat_assert_fail(#expr, __FILE__, __LINE__)))
#endif
