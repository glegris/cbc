/* <dlfcn.h> -- POSIX, x86 only (dynamic loading has no JVM backend
 * equivalent). Constants from glibc's bits/dlfcn.h. */
#ifndef _CFLAT_DLFCN_H
#define _CFLAT_DLFCN_H

const int RTLD_LAZY     = 0x0001;
const int RTLD_NOW      = 0x0002;
const int RTLD_NOLOAD   = 0x0004;
const int RTLD_DEEPBIND = 0x0008;
const int RTLD_LOCAL    = 0x0000;
const int RTLD_GLOBAL   = 0x0100;
const int RTLD_NODELETE = 0x1000;

extern void* dlopen(char* filename, int flag);
extern char* dlerror(void);
extern void* dlsym(void* handle, char* symbol);
extern int dlclose(void* handle);

#endif
