#include "stdio.h"
#include "decloverride.h"

int f(void) { return 77; }

int
main(void)
{
    printf("%d\n", f());
    return 0;
}
