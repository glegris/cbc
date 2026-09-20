#include "stdio.h"

/* Three real bugs found by running a subset of the GCC c-torture
 * execute tests through this compiler (see the session that added
 * this file): each one made a translation unit compile successfully
 * (so it was never caught by a hard compile error) but silently
 * compute the wrong value. */

int a = 0x101;
int b = 0x100;

int
narrowing_in_condition(void)
{
    /* A narrowing cast used directly as a boolean condition (not
     * stored into an equally-narrow variable first, which happened to
     * mask the bug via the store's own width): CastNode#isEffectiveCast()
     * treated any narrowing cast as a free no-op and IRGenerator
     * dropped it entirely, so the condition tested the ORIGINAL,
     * untruncated value instead of the narrowed one. */
    return (((unsigned char) (unsigned long long) ((a ? a : 1) & (a * b)))
            ? 0 : 1);
}

int
narrowing_wraparound(void)
{
    /* Same root cause, in a loop update: without a real truncating
     * instruction, this either never wraps around (infinite loop) or
     * wraps incorrectly. */
    int l = -1;
    int iterations = 0;
    for (; l != 0; l = (unsigned char)(l - 1)) {
        iterations++;
        if (iterations > 1000) return -1;  /* would-be infinite loop */
    }
    return iterations;  /* -1 down to 0, wrapping through unsigned char: 255 steps */
}

unsigned int
long_long_mixed_arith(unsigned long long x)
{
    /* usualArithmeticConversion() had no notion of "long long" at all,
     * so mixing one with a plain "int" (here, the shift count) silently
     * fell through to plain "int", truncating x's own upper 32 bits
     * before the shift ever ran and shifting what's left with a 32-bit
     * (not 64-bit) shift instruction. */
    if (x == 0) return 0;
    return (unsigned int)(x >> 32);
}

int
narrow_promotion_vs_int(unsigned char x, int expected)
{
    /* arithmeticImplicitCast() compared the *promoted* type of each
     * operand against the target type, not the operand's own actual
     * (pre-promotion) type -- so when a narrow operand's promotion
     * happened to already equal the target (e.g. "unsigned char" here
     * promoting to plain "int", the same target chosen for the "int"
     * on the other side), the promotion was never actually materialized
     * as a cast on the AST, leaving the two sides of "==" at genuinely
     * different widths. */
    return expected == x;
}

int
main(int argc, char **argv)
{
    printf("%d;", narrowing_in_condition());
    printf("%d;", narrowing_wraparound());
    printf("%u;", long_long_mixed_arith(0x25ff00ff00ULL));
    unsigned char c = 246;
    printf("%d;", narrow_promotion_vs_int((unsigned char)(c + 1), 247));
    return 0;
}
