#include "stdio.h"

/* Relational (< <= > >=) must bind tighter than equality (== !=),
 * which must bind tighter than "&", which must bind tighter than "^",
 * which must bind tighter than "|" -- per C99 6.5's own precedence
 * table. This used to be badly wrong: relational and equality were
 * merged into a single level sitting ABOVE (i.e. binding looser than)
 * "|"/"^"/"&", found via the c-testsuite project's own tests (an
 * N-queens solver using "x+i < 8 & y+i < 8"-style conditions without
 * fully parenthesizing every comparison silently computed garbage).
 */

int
main(int argc, char **argv)
{
    int x = 3, i = 5;

    /* real C: ((x+i)<8) & ((x-i)>=0) = (8<8) & (-2>=0) = 0 & 0 = 0 */
    printf("%d;", x + i < 8 & x - i >= 0);

    /* real C: (x==3) & (i==5) = 1 & 1 = 1 */
    printf("%d;", x == 3 & i == 5);

    /* the classic gotcha: "&" binds looser than "==", so this is
     * x & (5==0), i.e. x & 0 = 0, NOT (x&5)==0 (which would be 0 too
     * here, so pick a value where the two readings actually differ:
     * x=3 -> (x&5)==0 is (1)==0 -> 0; x&(5==0) is 3&0 -> 0 -- same;
     * use x=1 instead: (x&5)==0 -> (1)==0 -> 0; x&(5==0) -> 1&0 -> 0;
     * still the same -- pick x=4: (x&5)==0 -> (4)==0 -> 0;
     * x&(5==0) -> 4&0 -> 0 -- need x&5 nonzero and x!=0 for the two
     * readings to diverge: x=2 -> (x&5)==0 -> (2&5=0)==0 -> 1;
     * x&(5==0) -> 2&0 -> 0. */
    printf("%d;", 2 & 5 == 0);

    /* relational tighter than equality: (1<2)==(3<4) = 1==1 = 1 */
    printf("%d;", 1 < 2 == 3 < 4);

    /* "|"/"^" looser than "&": 1 | (2 & 3) = 1 | 2 = 3, not (1|2)&3 = 3
     * (same here by coincidence) -- use values that actually diverge:
     * 4 | 1 & 3 -> real C: 4 | (1&3) = 4 | 1 = 5; wrong precedence
     * (("|" tighter): (4|1)&3 = 5&3 = 1. */
    printf("%d;", 4 | 1 & 3);

    printf("\n");
    return 0;
}
