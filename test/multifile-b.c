#include "stdio.h"

extern int compute(int x);
extern int shared_var;

static int helper_b(int x) { return x * 2; }

int
main(void)
{
    printf("%d;%d;%d;\n", compute(5), helper_b(5), shared_var);
    return 0;
}
