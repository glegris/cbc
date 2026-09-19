/* <stdint.h> -- C99 7.18 (the fixed-width part; intN_t/uintN_t and the
 * pointer-width pair only, not every optional "least"/"fast" variant).
 * The 64-bit types are "long long"/"unsigned long long", not "long":
 * long long is a fixed 8 bytes on both backends already (see the
 * "Language additions" section), where plain "long" isn't (see
 * limits.h's own comment on that). intptr_t/uintptr_t are "long"
 * instead, deliberately: it's already exactly pointer-width on both
 * backends by this compiler's own design (4 bytes on x86, 8 on the JVM
 * backend, matching each backend's own pointer size). */
#ifndef _CFLAT_STDINT_H
#define _CFLAT_STDINT_H

typedef char int8_t;
typedef unsigned char uint8_t;
typedef short int16_t;
typedef unsigned short uint16_t;
typedef int int32_t;
typedef unsigned int uint32_t;
typedef long long int64_t;
typedef unsigned long long uint64_t;

typedef long intptr_t;
typedef unsigned long uintptr_t;

#define INT8_MIN (-128)
#define INT8_MAX 127
#define UINT8_MAX 255
#define INT16_MIN (-32768)
#define INT16_MAX 32767
#define UINT16_MAX 65535
#define INT32_MIN (-2147483647 - 1)
#define INT32_MAX 2147483647
#define UINT32_MAX 4294967295U
#define INT64_MIN (-9223372036854775807LL - 1)
#define INT64_MAX 9223372036854775807LL
#define UINT64_MAX 18446744073709551615ULL

#endif
