#include "stdio.h"

int
sumto(int n)
{
    int t = 0;
    for (int i = 1; i <= n; i++) t = t + i;
    return t;
}

int
nested(void)
{
    int c = 0;
    for (int a = 0; a < 3; a++)
        for (int b = 0; b < 3; b++)
            c++;
    return c;
}

int
scope(void)
{
    int i = 99;                     /* independent of the loop index */
    for (int i = 0; i < 3; i++) { }
    return i;
}

int
main(int argc, char **argv)
{
    printf("%d;%d;%d\n", sumto(100), nested(), scope());
    return 0;
}
