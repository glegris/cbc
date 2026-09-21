/* A case label must be an integer *constant expression* (C99 6.8.4.2p3),
 * not just any expression transformExpr() can turn into runtime code --
 * visit(SwitchNode) used to assume every case value was already a raw
 * IR "Int" literal, crashing (ClassCastException) on anything else, e.g.
 * stb_image.h's own "#define STBI__COMBO(a,b) ((a)*8+(b))" used as
 * "case STBI__COMBO(1,2):". Now folded the same way a static
 * initializer already is (foldStaticConstant), including the extra
 * integer operators (%, &, |, ^, <<, >>, ~) that only make sense for a
 * constant expression, not a static initializer. */
#include "stdio.h"

#define COMBO(a,b) ((a)*8+(b))

static int
classify(int a, int b)
{
    switch (COMBO(a, b)) {
        case COMBO(1, 2): return 100;
        case COMBO(3, 4): return 200;
        case (1 << 3) | 1: return 300;
        case ~(-6): return 400;   /* ~(-6) == 5 */
        default: return -1;
    }
}

int
main(void)
{
    printf("%d;%d;%d;%d;%d;",
            classify(1, 2), classify(3, 4), classify(1, 1),
            classify(0, 5), classify(9, 9));
    return 0;
}
