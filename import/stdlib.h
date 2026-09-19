/* <stdlib.h> -- C99 7.20 */
#ifndef _CFLAT_STDLIB_H
#define _CFLAT_STDLIB_H

#include "stddef.h"  /* for size_t */

extern void exit(int status);
extern void* calloc(size_t nmemb, size_t size);
extern void* malloc(size_t size);
extern void free(void* ptr);
extern void* realloc(void* ptr, size_t size);
extern int system(char* command);

/* Numeric conversions -- already implemented in StandardRuntime.java on
 * the JVM backend (net.loveruby.cflat.sysdep.jvm.runtime), and by the
 * real libc on x86. */
extern int abs(int x);
extern long labs(long x);
extern int atoi(char* s);
extern long atol(char* s);
extern double atof(char* s);

#endif
