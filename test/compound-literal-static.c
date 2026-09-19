#include "stdio.h"

struct Point {
    int x;
    int y;
};

/* file-scope (static storage duration) compound literal, as a global's
 * whole initializer */
struct Point gp = (struct Point){7, 8};

/* ... and as an already-braced nested element of one */
int[2] pair = { (int){100}, (int){200} };

int
f(void)
{
    /* a "static" local's whole initializer gets the same treatment:
     * initialized once, not on every call */
    static int[3] s = (int[3]){10, 20, 30};
    s[0] = s[0] + 1;
    return s[0];
}

int
main(int argc, char **argv)
{
    printf("%d;%d;", gp.x, gp.y);
    printf("%d;%d;", pair[0], pair[1]);
    int a = f();
    int b = f();
    printf("%d;%d\n", a, b);
    return 0;
}
