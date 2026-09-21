#include "stdio.h"

/* Each declarator in a "T d1, d2, ...;" comma list must get its own
 * pointer level, not blindly inherit whatever the first declarator's
 * own "*" happened to leave behind: "int *p, q;" -- "q" must come out
 * as plain "int", not "int*". defvars_body() used to share a single
 * already-postfix-applied TypeNode across every declarator in the
 * list, so "q" here used to silently become a pointer too (an 8-byte
 * JVM long instead of a 4-byte int, catchable via sizeof(q)). Found
 * via the c-testsuite project's own tests. See typePostfix().
 */

int
main(void)
{
    int x = 5;
    int *p, q = 10;
    p = &x;

    int a, *b, **c;
    a = 1;
    b = &a;
    c = &b;

    printf("%d;%d;%d;%d;%d;%d;\n",
           *p, q, (int)sizeof(q), a, *b, **c);
    return 0;
}
