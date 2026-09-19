/* <strings.h> -- POSIX, not C99 itself, but kept for the same
 * case-insensitive string functions real C programs commonly use. */
#ifndef _CFLAT_STRINGS_H
#define _CFLAT_STRINGS_H

#include "stddef.h"

extern int strcasecmp(char* str1, char* str2);
extern int strncasecmp(char* str1, char* str2, size_t len);
extern char* index(char* src, int ch);
extern char* rindex(char* src, int ch);

#endif
