#include "stdio.h"

int
main(void)
{
    puts = NULL;   // causes core dump
    return 0;
}
