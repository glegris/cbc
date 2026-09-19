/* <stdarg.h> -- C99 7.15. va_init()/va_next() are this compiler's own
 * two primitives underneath the standard va_list type (see
 * lib/stdarg.cb for their implementation, and the JVM backend's own
 * class doc on CodeGenerator for how it makes them work there despite
 * having no real, contiguous call stack to point into). There is no
 * separate va_start()/va_arg()/va_end() macro trio: write
 *     va_list ap = va_init(&last_named_param);
 *     ... = va_next(&ap);
 * instead. */
#ifndef _CFLAT_STDARG_H
#define _CFLAT_STDARG_H

typedef unsigned long va_arg_t;
typedef va_arg_t* va_list;

extern va_list va_init(void* arg);
extern void* va_next(va_list* ap);

#endif
