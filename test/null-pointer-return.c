#include "stdio.h"
#include "stddef.h"

/* Regression test for a JVM-backend bug: a named constant's substituted
 * value (see IRGenerator#visit(VariableNode)) kept the literal's own
 * width instead of the reference site's resolved type, so returning
 * NULL ("const void* NULL = 0;" in stddef.h -- a 4-byte int literal
 * substitution) from a pointer-returning function stored a single-slot
 * JVM int into the two-slot long return slot a pointer actually uses,
 * corrupting the JVM's local variable table (a VerifyError at class
 * load, not just a wrong value). */

void*
find_none(void)
{
    return NULL;
}

int*
find_int_none(int x)
{
    if (x > 0) return NULL;
    return NULL;
}

int
main(int argc, char **argv)
{
    void *a = find_none();
    int *b = find_int_none(1);
    printf("%d;%d\n", a == NULL, b == NULL);
    return 0;
}
