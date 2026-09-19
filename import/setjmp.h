/* <setjmp.h> -- C99 7.13. x86 only: this calls straight into the real
 * libc's own setjmp/longjmp, so jmp_buf's size below must match glibc's
 * (156 bytes on Linux/i386/glibc 2.3) -- there is no JVM backend
 * implementation of real non-local control flow. */
#ifndef _CFLAT_SETJMP_H
#define _CFLAT_SETJMP_H

typedef char[156] jmp_buf;
typedef char[156] sigjmp_buf;

extern int setjmp(jmp_buf buf);
extern int sigsetjmp(sigjmp_buf buf, int savesigs);
extern void longjmp(jmp_buf buf, int value);
extern void siglongjmp(sigjmp_buf buf, int value);

#endif
