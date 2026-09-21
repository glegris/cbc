/* A batch of real-world-C gaps/bugs found compiling minimp3.h (a real,
 * unrelated third-party MP3 decoder) and decoding an actual MP3 through
 * this compiler's own JVM backend -- see demos/minimp3. */
#include "stdio.h"

/* 1. An array size in a declarator used to require a single raw integer
 *    literal token; minimp3.h's own "float mdct_overlap[2][9*32];" needs
 *    a folded constant expression instead. */
static int table[2][9 * 32];

/* 2. A decimal integer literal's explicit "U"/"u" suffix used to be
 *    ignored for overflow purposes (only hex/octal got the "fall back to
 *    unsigned" treatment) -- minimp3.h's own UINT64_MAX definition
 *    ("18446744073709551615ULL") crashed the compiler outright. */
#define BIG_UNSIGNED 18446744073709551615ULL

/* 3. Compound assignment (+= -= *= /=) on a float/double operand used to
 *    be rejected outright -- minimp3.h's own L3_ldexp_q2 does exactly
 *    this ("y *= g_expfrac[...]..."). */
static float
scale_up(float y, float factor)
{
    y *= factor;
    return y;
}

/* 4. Indexing one level into a multi-dimension array member (giving
 *    another ARRAY, not a scalar) used to decay to the wrong address
 *    when read back through a pointer -- minimp3.h's own
 *    "mp3dec_scratch_t::ist_pos[2][39]", indexed by channel and passed
 *    around as a "uint8_t *". */
typedef struct {
    unsigned char rows[2][5];
} Rows;

static Rows g_rows;

static unsigned char *
row(int i)
{
    return g_rows.rows[i];
}

int
main(void)
{
    table[1][5] = 42;
    printf("%d;", table[1][5]);

    printf("%d;", (BIG_UNSIGNED >> 32) == 0xFFFFFFFFULL ? 1 : 0);

    printf("%.1f;", scale_up(2.0f, 3.5f));

    unsigned char *p0 = row(0);
    unsigned char *p1 = row(1);
    p0[0] = 11;
    p0[4] = 12;
    p1[0] = 21;
    p1[4] = 22;
    printf("%d;%d;%d;%d;", p0[0], p0[4], p1[0], p1[4]);
    return 0;
}
