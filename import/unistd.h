/* <unistd.h> -- POSIX, x86 only. */
#ifndef _CFLAT_UNISTD_H
#define _CFLAT_UNISTD_H

#include "sys/types.h"

extern void _exit(int status);
extern pid_t fork(void);
extern pid_t getpid(void);
extern pid_t getppid(void);
extern unsigned int sleep(unsigned int secs);

#endif
