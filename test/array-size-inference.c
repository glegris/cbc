#include "stdio.h"

/* "T x[] = {...};" must infer the array's length from its initializer
 * (C99 6.7.8p22) -- plain positional elements contribute their count,
 * and a "[N] = ..." designator can stretch the length further (C99
 * allows both in the same initializer, continuing positionally right
 * after a designator). TypeResolver binds the variable to an
 * *incomplete* array type long before it ever sees the initializer,
 * so checkVariable() has to backfill the real length once it can see
 * the initializer -- see resolveIncompleteArrayLength().
 */

int a[] = {1, 2, 3, 4};
int b[] = {5, [2] = 20, 30};

int
main(void)
{
    int c[] = {7, 8, 9};
    printf("%d;%d;%d;%d;%d;%d;%d;%d;\n",
           (int)(sizeof(a) / sizeof(int)), a[0] + a[3],
           (int)(sizeof(b) / sizeof(int)), b[0] + b[1] + b[2] + b[3],
           (int)(sizeof(c) / sizeof(int)), c[0] + c[2],
           b[1], b[2]);
    return 0;
}
