#include "stdio.h"
#include "stddef.h"

struct point { int x; int y; };

/* "&" of a plain global, as a global's own initializer */
int gx = 5;
int *gp = &gx;

/* a table of pointers to named globals */
struct point origin = {0, 0};
struct point unit = {1, 1};
struct point*[2] presets = { &origin, &unit };

/* "&" of an anonymous compound literal -- needs a synthesized,
 * static-storage backing object, since it has no named variable of its
 * own to take the address of */
struct point*[3] waypoints = {
    &(struct point){0, 0},
    &(struct point){5, 3},
    &(struct point){9, 9}
};

/* "&" of a compound literal nested inside a larger struct's own
 * initializer, not just as the initializer's whole value */
struct holder { int *p; int y; };
struct holder h = { &(int){42}, 6 };

/* a static, self-referential linked list built entirely out of "&" of
 * other globals -- including NULL, which is itself a named constant
 * ("const void* NULL = 0;" in stddef.h), not a literal */
struct node { int val; struct node *next; };
struct node c = {3, NULL};
struct node b = {2, &c};
struct node a = {1, &b};

int
f(void)
{
    /* the same "&" support for a "static" local's own initializer */
    static struct point *p = &origin;
    return p->x + p->y;
}

int
main(int argc, char **argv)
{
    printf("%d;%d;", gx, *gp);
    printf("%d;%d;%d;%d;", presets[0]->x, presets[0]->y, presets[1]->x, presets[1]->y);
    printf("%d;%d;%d;%d;%d;%d;",
        waypoints[0]->x, waypoints[0]->y,
        waypoints[1]->x, waypoints[1]->y,
        waypoints[2]->x, waypoints[2]->y);
    printf("%d;%d;", *h.p, h.y);
    struct node *n = &a;
    while (n != NULL) {
        printf("%d;", n->val);
        n = n->next;
    }
    printf("%d\n", f());
    return 0;
}
