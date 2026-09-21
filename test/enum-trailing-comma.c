#include "stdio.h"

/* C99 6.7.2.2p1 explicitly allows one trailing "," after an enum's
 * last enumerator ("enum E { x, y, z, };"), same as this grammar
 * already allows for an array/struct aggregate literal -- but
 * defenum() required a real enumerator right after every ",",
 * rejecting one that's immediately followed by "}". Found via the
 * c-testsuite project's own tests.
 */

enum Color {
    RED,
    GREEN,
    BLUE,
};

int
main(void)
{
    enum Color c = GREEN;
    printf("%d;%d;%d;%d;\n", RED, GREEN, BLUE, c);
    return 0;
}
