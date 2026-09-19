/* <limits.h> -- C99 7.10 (well, 5.2.4.2.1, but this is the header that
 * carries it). char/short/int/long long are the same fixed width on
 * both backends, so those are given as plain literal constants; "long"
 * itself is NOT (4 bytes on the x86 backend, 8 on the JVM backend --
 * see the JVM backend's own README section), so LONG_MIN/LONG_MAX/
 * ULONG_MAX are written as portable expressions the *compiler* resolves
 * against long's true width on whichever backend it's actually
 * targeting, rather than a preprocessor-time literal that would be
 * wrong on one of the two. The tradeoff: unlike every other macro here,
 * these three aren't usable in a "#if" constant expression (the
 * preprocessor evaluates those on its own, long before any backend-
 * specific width is known) -- only in ordinary compiled code. */
#ifndef _CFLAT_LIMITS_H
#define _CFLAT_LIMITS_H

#define CHAR_BIT 8

#define SCHAR_MIN (-128)
#define SCHAR_MAX 127
#define UCHAR_MAX 255
/* plain "char" defaults to signed on both backends. */
#define CHAR_MIN SCHAR_MIN
#define CHAR_MAX SCHAR_MAX

#define SHRT_MIN (-32768)
#define SHRT_MAX 32767
#define USHRT_MAX 65535

#define INT_MIN (-2147483647 - 1)
#define INT_MAX 2147483647
#define UINT_MAX 4294967295U

#define LONG_MAX ((long)(~0UL >> 1))
#define LONG_MIN (-LONG_MAX - 1)
#define ULONG_MAX (~0UL)

#define LLONG_MIN (-9223372036854775807LL - 1)
#define LLONG_MAX 9223372036854775807LL
#define ULLONG_MAX 18446744073709551615ULL

#endif
