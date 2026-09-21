#include "stdio.h"

/* C99 6.5.3.3p5: "!"'s own result always has type "int", regardless of
 * its operand's type -- found via the c-testsuite project's own tests
 * (sizeof(!x) for a "char x" must be sizeof(int), not sizeof(char)).
 * UnaryOpNode#type() used to just proxy its operand's own type
 * unconditionally for every unary operator, "!" included.
 */

int
main(int argc, char **argv)
{
    char a = 5;
    printf("%d;%d;%d;\n",
           (int)sizeof(a), (int)sizeof(int), (int)sizeof(!a));
    return 0;
}
