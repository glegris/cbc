/* Each declarator in a struct/union's own "T d1, d2, ...;" comma list
 * (e.g. stb_image.h's own "stbi__uint32 img_x, img_y;") re-applies its
 * own "*"/"[N]"/"[]"/":width" to the same shared base type, exactly
 * like a plain variable declaration's own comma list already does --
 * see slot()'s own comment. */
#include "stdio.h"

struct point {
    int x, y;
    char *name, *label;
    unsigned a : 3, b : 5;
};

int
main(void)
{
    struct point p;
    p.x = 1;
    p.y = 2;
    p.name = "n";
    p.label = "l";
    p.a = 6;
    p.b = 20;
    printf("%d;%d;%s;%s;%d;%d;", p.x, p.y, p.name, p.label, p.a, p.b);
    return 0;
}
