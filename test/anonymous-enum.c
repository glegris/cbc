/* C99 6.7.2.3: an enum's own tag is optional. "enum { A, B, C };" with
 * neither a tag nor a declared variable is real C's single most common
 * way to just define a handful of named int constants -- found while
 * getting stb_image.h's own "enum { STBI_default = 0, ... };" to
 * compile. */
#include "stdio.h"

enum
{
    ONE = 1,
    TWO,
    THREE
};

int
main(void)
{
    printf("%d;%d;%d;", ONE, TWO, THREE);
    return 0;
}
