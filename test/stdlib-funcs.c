#include "stdio.h"
#include "stdlib.h"

int
cmp_int(void *a, void *b)
{
    return *(int*)a - *(int*)b;
}

int
main(int argc, char **argv)
{
    div_t d = div(7, 2);
    printf("%d;%d;", d.quot, d.rem);
    ldiv_t ld = ldiv(17, 5);
    printf("%d;%d;", (int)ld.quot, (int)ld.rem);

    char *end;
    long l = strtol("  -123abc", &end, 10);
    printf("%d;%s;", (int)l, end);
    unsigned long u = strtoul("ff", NULL, 16);
    printf("%d;", (int)u);
    double d2 = strtod("3.14e2xyz", &end);
    printf("%d;%s;", (int)d2, end);

    srand(42);
    int r1 = rand();
    srand(42);
    int r2 = rand();
    printf("%d;", r1 == r2);

    int[6] arr = {5, 3, 1, 4, 1, 2};
    qsort(arr, 6, sizeof(int), cmp_int);
    printf("%d;%d;%d;%d;%d;%d;", arr[0], arr[1], arr[2], arr[3], arr[4], arr[5]);

    int key = 4;
    int *found = bsearch(&key, arr, 6, sizeof(int), cmp_int);
    printf("%d;%d\n", found != NULL, *found);
    return 0;
}
