/* "east const" ("char const *p", as opposed to the leading "const char
 * *p"): real C's own declaration-specifiers allow a type-qualifier on
 * either side of the type name itself. Found while getting
 * stb_image.h's own public API declarations (e.g. "extern const char
 * *stbi_failure_reason(void);" spelled as "stbi_uc const *buffer")
 * to compile. */
#include "stdio.h"

static int
lenOf(char const *s)
{
    int n = 0;
    while (*s != '\0') {
        n++;
        s++;
    }
    return n;
}

int
main(void)
{
    char const *msg = "hello";
    printf("%d;%s;", lenOf(msg), msg);
    return 0;
}
