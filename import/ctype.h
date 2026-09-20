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

/* The rest of <ctype.h>: none of these need memory access or anything
 * else host-specific, so -- unlike the eight above, hand-implemented in
 * StandardRuntime.java before this header even existed -- these are
 * just written directly in portable C, "static" so that two different
 * .c files both including this header don't collide (see assert.h's own
 * doc comment for exactly why). This runs identically on both backends,
 * with no JVM-specific implementation to keep in sync. */
static int iscntrl(int c) {
    return ((unsigned int)c < 0x20 || c == 0x7F) ? 1 : 0;
}

static int isblank(int c) {
    return (c == ' ' || c == '\t') ? 1 : 0;
}

static int isascii(int c) {
    return (c >= 0 && c <= 0x7F) ? 1 : 0;
}

static int isprint(int c) {
    return (c >= 0x20 && c <= 0x7E) ? 1 : 0;
}

static int isgraph(int c) {
    return (c >= 0x21 && c <= 0x7E) ? 1 : 0;
}

static int ispunct(int c) {
    return (isprint(c) && !isspace(c) && !isalnum(c)) ? 1 : 0;
}

static int isxdigit(int c) {
    return ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) ? 1 : 0;
}

#endif
