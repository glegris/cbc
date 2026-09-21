/* Three related static/global-initializer gaps found while getting
 * stb_image.h to compile, all in IRGenerator's foldStaticConstant():
 *   - a bare function name decays to its own (link-time-constant)
 *     address, same as visit(VariableNode) already resolves it at
 *     runtime -- e.g. a struct of callback function pointers.
 *   - a basic-arithmetic constant expression of two foldable operands
 *     (e.g. stb_image.h's own "static float stbi__h2l_gamma_i =
 *     1.0f/2.2f;").
 *   - "extern int f(void) { ... }": a function *definition* marked
 *     "extern" (a no-op -- a top-level function is externally linked
 *     by default anyway) used to only be accepted as a bare
 *     declaration (see storage()'s own comment).
 */
#include "stdio.h"

typedef struct {
    int (*add)(int a, int b);
    int (*sub)(int a, int b);
} ops;

extern int
plus(int a, int b)
{
    return a + b;
}

static int
minus(int a, int b)
{
    return a - b;
}

/* Trailing comma after the last initializer element (C99 6.7.8p22, same
 * as defenum() already allowed) -- aggregate_literal()'s own comma loop
 * needed a 2-token LOOKAHEAD for the exact same reason defenum()'s
 * already has one, or it always assumed another element followed. */
static ops theOps = { plus, minus, };

static float half = 1.0f / 2.0f;
static int combined = (1 << 3) | (1 << 1);

int
main(void)
{
    /* Calling through theOps.add/theOps.sub directly would hit a
     * separate, pre-existing, documented JVM-backend limitation
     * (indirect calls are only supported through a plain function-
     * pointer *variable* -- see CodeGenerator#indirectCallSignature's
     * own doc comment) -- copying to a local variable first sidesteps
     * it; what this test actually exercises is that theOps' own static
     * initializer (a struct of bare function names) built the right
     * addresses in the first place. */
    int (*add)(int, int) = theOps.add;
    int (*sub)(int, int) = theOps.sub;
    printf("%d;%d;", add(5, 3), sub(5, 3));
    printf("%d;%d;", (int)(half * 100.0f), combined);
    return 0;
}
