#include "stdio.h"

/* A struct/union tag has always been optional (since K&R) -- "struct {
 * ... } v;" and "struct Name { ... } v;" (a real tag, but defined
 * right where it's first used as a type) both just define the
 * struct/union as a side effect of being used as a type, exactly like
 * a local struct/union definition inside a function body already does.
 * Found via the c-testsuite project's own tests. See typeref_base()'s
 * own comment for why top_defs()'s "is this a variable declaration"
 * LOOKAHEAD needed fixing alongside this.
 */

struct { int a; int b; int c; } g = {1, 2, 3};

typedef struct {
    int x;
    int y;
} Point;

struct Named { int n; };

int
main(void)
{
    union { int a; int b; } u;
    u.a = 1;
    u.b = 3;
    /* "u.a" and "u.b" share the same storage (it's a union), so both
     * read back as 3 here -- confirms this really produced a union
     * type, not (say) a struct with two independent members. */

    struct Named nm;
    nm.n = 42;

    Point p;
    p.x = 5;
    p.y = 6;

    printf("%d;%d;%d;%d;%d;%d;\n",
           g.a + g.b + g.c, u.a, u.b, nm.n, p.x, p.y);
    return 0;
}
