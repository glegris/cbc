#include "stdio.h"

/* Several standard C99 integer type-specifier combinations that used
 * to have no grammar production at all: bare "signed"/"unsigned"
 * (meaning plain "int"/"unsigned int"), "signed char/short/long/long
 * long", and any of "short/long/long long/unsigned ..."'s own
 * optional trailing "int" ("long int", "unsigned short int", etc --
 * see typeref_base()'s own comment for why each combination is
 * spelled out explicitly rather than factored into a shared
 * sub-production).
 */

int
main(void)
{
    signed s = -1;
    unsigned u = 1;
    signed char sc = -2;
    long int li = 100000;
    short int si = 30000;
    unsigned long int uli = 4000000000u;
    unsigned short int usi = 60000;
    unsigned long long int ulli = 100;
    signed long long int slli = -100;
    printf("%d;%u;%d;%ld;%d;%lu;%u;%llu;%lld;\n",
           s, u, sc, li, si, uli, usi, ulli, slli);
    return 0;
}
