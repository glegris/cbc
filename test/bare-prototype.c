#include "stdio.h"

/* A bare, non-"extern" top-level function prototype ("int f(char *);",
 * a header's usual content for a function it doesn't itself define)
 * used to have no grammar path at all: only an "extern"-prefixed
 * prototype was accepted. Fixed by parsing a function's shared
 * "storage() typeref() name() (params)" prefix once and branching on
 * the single following token ("{" a body, ";" a bare declaration) --
 * see funcDefOrDecl()'s own comment.
 *
 * A prototype's own parameter names are also optional ("int f(char
 * *);", with no body to ever refer to one by name) -- see param()'s
 * own comment.
 */

int add(int, int);

int
add(int a, int b)
{
    return a + b;
}

int
main(void)
{
    printf("%d;\n", add(2, 3));
    return 0;
}
