#include "stdio.h"

/* Regression test for a JVM-backend bug: CastNode#isEffectiveCast()'s
 * size-only heuristic treats a narrowing cast ("(int)long_expr") as a
 * free, no-op reinterpretation -- true on x86 (same register, just
 * fewer significant bits matter from here on), but not on the JVM,
 * where "long" and "int" occupy a different number of stack/local
 * slots: IRGenerator's visit(CastNode) drops the "not effective" cast
 * entirely, so returning one directly (not through an intermediate
 * assignment, which happens to coerce width on its own) tried to store
 * a two-slot long into a function's one-slot int return-value slot,
 * corrupting the JVM's local variable table (a VerifyError at class
 * load, not just a wrong value). */

long
g(void)
{
    return 100000;
}

int
narrow(void)
{
    return (int)(g() + 32767);
}

int
main(int argc, char **argv)
{
    printf("%d\n", narrow());
    return 0;
}
