#include "stdio.h"

struct Point {
    int x;
    int y;
};

struct Rect {
    struct Point tl;
    struct Point br;
};

int
main(int argc, char **argv)
{
    /* chained member designator: ".a.b = x", no nested braces needed */
    struct Rect r = { .tl.x = 1, .tl.y = 2, .br.x = 3, .br.y = 4 };
    printf("%d;%d;%d;%d;", r.tl.x, r.tl.y, r.br.x, r.br.y);

    /* chained index+member designator: "[i].member = x" */
    struct Point[3] pts = { [0].x = 10, [0].y = 11, [2].x = 30, [2].y = 31 };
    printf("%d;%d;%d;%d;%d;%d;",
        pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y);

    /* [index] as a general compile-time constant expression */
    int[8] arr = { [1 + 1] = 5, [(2 * 3) - 1] = 9, [8 - 8] = 1 };
    printf("%d;%d;%d;%d;", arr[0], arr[2], arr[5], arr[7]);

    /* the same spot designated twice: the last value given wins */
    struct Point p = { .x = 1, .x = 99, .y = 2 };
    printf("%d;%d\n", p.x, p.y);
    return 0;
}
