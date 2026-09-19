#include "stdio.h"
int
main(int argc, char **argv)
{
    int a = 5;
    int c = 5;
    _Bool eq;
    _Bool lt;
    _Bool t = 1;
    _Bool f = 0;
    eq = (a == c);
    lt = (a < c);
    printf("%d;%d;%d;%d;%d;%d;%d\n", eq, lt, t, f, t && f, t || f, sizeof(_Bool));
    return 0;
}
