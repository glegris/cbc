/* <errno.h> -- C99 7.5. Not attempting the standard's actual error
 * macros (EDOM/ERANGE/EILSEQ, ...) here since nothing in this
 * compiler's own runtime sets them; "errno" itself is declared purely
 * for code that reads/sets it directly, or expects the real libc's own
 * calls to (x86 only -- the JVM backend's own runtime doesn't). */
#ifndef _CFLAT_ERRNO_H
#define _CFLAT_ERRNO_H

extern char*[] sys_errlist;
extern int sys_nerr;
extern int errno;

#endif
