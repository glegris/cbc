/* <stddef.h> -- C99 7.17 */
#ifndef _CFLAT_STDDEF_H
#define _CFLAT_STDDEF_H

const void* NULL = 0;
typedef unsigned long size_t;
typedef long ptrdiff_t;

/* offsetof(type, member): the usual "&((type*)0)->member" trick, cast
 * down to size_t the same way a real byte offset always is. */
#define offsetof(type, member) ((size_t)&((type*)0)->member)

#endif
