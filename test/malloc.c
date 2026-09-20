#include "stdio.h"
#include "stdlib.h"
#include "string.h"

struct node { int val; struct node *next; };

int
main(int argc, char **argv)
{
    int *p = malloc(sizeof(int) * 5);
    for (int i = 0; i < 5; i++) p[i] = i * i;
    printf("%d;%d;%d;%d;%d;", p[0], p[1], p[2], p[3], p[4]);
    free(p);

    int *z = calloc(3, sizeof(int));
    printf("%d;%d;%d;", z[0], z[1], z[2]);
    free(z);

    int *r = malloc(sizeof(int) * 2);
    r[0] = 10; r[1] = 20;
    r = realloc(r, sizeof(int) * 4);
    r[2] = 30; r[3] = 40;
    printf("%d;%d;%d;%d;", r[0], r[1], r[2], r[3]);
    free(r);

    /* exercises the free-list: 100 allocate/free cycles must all reuse
     * the same handful of freed blocks rather than exhausting the heap */
    struct node *head = NULL;
    for (int i = 0; i < 100; i++) {
        struct node *n = malloc(sizeof(struct node));
        n->val = i;
        n->next = head;
        head = n;
    }
    int sum = 0;
    struct node *cur = head;
    while (cur != NULL) {
        sum += cur->val;
        struct node *next = cur->next;
        free(cur);
        cur = next;
    }
    printf("%d\n", sum);
    return 0;
}
