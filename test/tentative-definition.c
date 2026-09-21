#include "stdio.h"

/* C89's "tentative definition": several file-scope declarations of the
 * same variable with no initializer -- or with only one of them ever
 * supplying one -- all refer to the *same* variable, not a "duplicated
 * definition" error; only two declarations that both supply an
 * initializer genuinely conflict. Found via the c-testsuite project's
 * own tests. See Declarations#addDefvar().
 */

int x;
int x = 3;
int x;

int y;
int y;

int
main(void)
{
    printf("%d;%d;\n", x, y);
    return 0;
}
