#include "stdio.h"

/* Lowercase (and mixed-case) integer suffixes -- "100ul", "5l", "7u",
 * "100ull", "5ll", "100llu" -- are just as valid/idiomatic C as
 * uppercase ones, but the lexer's <#INTSUFFIX> token fragment used to
 * only recognize uppercase "U"/"L", so e.g. "100ul" lexed as the two
 * separate tokens "100" (an INTEGER with no suffix) and the bare
 * identifier "ul", an immediate parse error.
 */

int
main(void)
{
    unsigned long a = 100ul;
    long b = 5l;
    unsigned c = 7u;
    unsigned long long d = 100ull;
    long long e = 5ll;
    unsigned long long f = 100llu;
    printf("%lu;%ld;%u;%llu;%lld;%llu;\n", a, b, c, d, e, f);
    return 0;
}
