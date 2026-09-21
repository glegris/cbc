#include "stdio.h"

/* Real C's own function-pointer declarator spelling ("int (*fp)();",
 * the "*" binding to the declarator itself, parenthesized so a
 * following "(params)" applies to "*fp" rather than to the return
 * type) -- unlike this project's own preexisting, simpler spelling
 * ("int ()* fp;", which sidesteps the parenthesization problem
 * entirely). Found via the c-testsuite project's own tests. See
 * funcPtrDeclarator()'s own comment.
 */

int add(int a, int b) { return a + b; }
int sub(int a, int b) { return a - b; }

int (*global_op)(int, int) = &add;

typedef int (*BinOp)(int, int);

struct Ops {
    int (*op)(int, int);
};

int
main(void)
{
    BinOp b = sub;
    struct Ops ops;
    ops.op = add;

    /* Calling through a struct member directly is a separate JVM
     * backend limitation (only a plain function-pointer variable is
     * supported as an indirect call's callee -- see CodeGenerator's
     * own indirectCallSignature comment), unrelated to parsing: copy
     * to a plain variable first, which the backend does support. */
    BinOp local_op = ops.op;

    printf("%d;%d;%d;\n", global_op(2, 3), b(5, 2), local_op(4, 4));
    return 0;
}
