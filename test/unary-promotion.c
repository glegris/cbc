#include "stdio.h"

/* C99 6.5.3.3p1: unary +/-/~ apply integer promotion to their operand
 * (a below-int operand is promoted to "int" first, same as a binary
 * operator's own operands), and the expression's type is that
 * promoted type. UnaryOpNode never did this at all -- found via the
 * c-testsuite project's own lshift-type.c, which detects a value's
 * signedness at runtime via "(M) < 0 || -(M) < 0": for an unsigned
 * short x, "-(x)" must behave as "-((int)x)" (always representable,
 * so "-(x) < 0" is true for any nonzero x, matching a signed value),
 * not as a same-width unsigned negation (which never looks negative).
 */

int
main(int argc, char **argv)
{
    unsigned short x = 1;

    /* -(x) must promote to int first: -((int)1) = -1, which is < 0.
     * A same-width unsigned negation of "x" would never be < 0. */
    printf("%d;", -(x) < 0);

    /* sizeof(-(x)) must be sizeof(int), not sizeof(unsigned short):
     * this only holds if unary "-" actually returns a promoted type,
     * not just its operand's own original type. */
    printf("%d;", (int)sizeof(-(x)) == (int)sizeof(int));

    /* same for unary "+" and "~". */
    printf("%d;", (int)sizeof(+(x)) == (int)sizeof(int));
    printf("%d;", (int)sizeof(~(x)) == (int)sizeof(int));

    printf("\n");
    return 0;
}
