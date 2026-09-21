/* C99 6.5.17's comma operator: "left, right" evaluates left for its side
 * effect only, then evaluates and yields right. Real C's own grammar
 * allows it in a for-loop's init/condition/increment clauses, inside
 * parentheses, and as a whole expression-statement -- but *not* as a
 * function-call argument, an initializer, or any other spot a bare ","
 * already means something else there (see Parser.jj's commaExpr()). */
#include "stdio.h"

int
main(void)
{
    int i, j;
    for (i = 0, j = 10; i < 5; i++, j--) {
    }
    printf("%d;%d;", i, j);

    int x = (i = 100, j = 200, i + j);
    printf("%d;%d;%d;", i, j, x);

    i = 1, j = 2;
    printf("%d;%d;", i, j);
    return 0;
}
