#include "stdio.h"

/* C99 6.4.4.1: a hex/octal integer constant (unlike a decimal one) is
 * allowed an unsigned fallback when its value doesn't fit a signed
 * type -- Parser.integerValue() used a signed Long.parseLong() for
 * hex/octal, throwing NumberFormatException for anything past
 * 0x7fffffffffffffff (e.g. ULLONG_MAX's own common all-ones bit-
 * pattern spelling, 0xffffffffffffffff). Separately, integerNode()
 * used to always type a suffix-less literal as plain "int" regardless
 * of its actual value, so 0xffffffff (fits "unsigned int", not a
 * signed "int") compared incorrectly against a real unsigned int.
 */

int
main(void)
{
    unsigned int a = 0xffffffff;
    unsigned long long b = 0xffffffffffffffff;
    int ok1 = (a == 0xffffffff);
    int ok2 = (b == 0xffffffffffffffffULL);
    printf("%u;%llu;%d;%d;\n", a, b, ok1, ok2);
    return 0;
}
