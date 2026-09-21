#include "stdio.h"
#include "stdlib.h"

/* Bit-fields ("unsigned int a : 3;") had no grammar support at all.
 * Implemented as: Slot carries a width + a bit-offset within a shared
 * "storage unit" that StructType#computeOffsets() packs consecutive
 * same-type bit-fields into (see its own comment); reads/writes are
 * lowered entirely with existing IR nodes (shift/mask/load/store), no
 * new IR node types or backend changes needed -- see
 * IRGenerator#extractBitField()/transformBitFieldRMW().
 */

struct Flags {
    unsigned int a : 1;
    unsigned int b : 3;
    unsigned int c : 4;
};

struct Signed {
    int x : 4;
};

struct Mixed {
    char tag;
    unsigned int flags : 5;
    int x;
    unsigned char small : 4;
    unsigned int big : 30;
};

union U {
    unsigned int raw;
    struct {
        unsigned int lo : 16;
        unsigned int hi : 16;
    } parts;
};

int
main(void)
{
    struct Flags f;
    f.a = 1;
    f.b = 5;
    f.c = 9;
    /* compound assignment / increment, including truncation on
     * overflow ("f.a++" on a 1-bit field wraps 1+1=2 down to 0) */
    f.b += 2;
    f.a++;

    struct Signed s;
    s.x = 7;
    int s1 = s.x;
    s.x = -1;
    int s2 = s.x;
    s.x = 8; /* out of range for 4 signed bits -> wraps to -8 */
    int s3 = s.x;

    struct Mixed *m = (struct Mixed *)malloc(sizeof(struct Mixed));
    m->tag = 'A';
    m->flags = 17;
    m->x = 1234;
    m->small = 12;
    m->big = 100000;
    int xBefore = m->x;
    m->flags = 0;
    m->small = 0;
    int xAfter = m->x; /* must be unchanged by neighboring bit-field writes */

    union U u;
    u.raw = 0x12345678;

    printf("%d;%d;%d;%d;%d;%d;%d;%d;%d;%d;%d;%d;%x;%x;\n",
           f.a, f.b, s1, s2, s3,
           (int)m->tag, m->flags, m->x, (int)m->small, (int)m->big,
           xBefore, xAfter, u.parts.lo, u.parts.hi);

    free(m);
    return 0;
}
