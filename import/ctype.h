/* <ctype.h> -- C99 7.4. Already implemented in StandardRuntime.java on
 * the JVM backend (net.loveruby.cflat.sysdep.jvm.runtime), and by the
 * real libc on x86 -- this header was simply missing before, so
 * neither backend could actually call these without declaring them by
 * hand first (every function call needs a prior declaration here, same
 * as any other extern one). */
#ifndef _CFLAT_CTYPE_H
#define _CFLAT_CTYPE_H

extern int isalnum(int c);
extern int isalpha(int c);
extern int isdigit(int c);
extern int isspace(int c);
extern int isupper(int c);
extern int islower(int c);
extern int toupper(int c);
extern int tolower(int c);

#endif
