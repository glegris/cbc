#include "stdio.h"
#include "stdlib.h"

/* C99 6.7.2.1p18's "flexible array member": an incomplete array
 * ("int data[];", no size at all) as a struct's very last member
 * contributes nothing to the struct's own sizeof -- it's a
 * placeholder for however many elements a caller allocates room for
 * past the struct itself (the classic "struct hack",
 * "malloc(sizeof(struct S) + n * sizeof(elem))"). ArrayType#allocSize()
 * used to fall back to its own decayed-to-pointer size (meant for an
 * array *parameter*, not a struct member) for any incomplete array,
 * silently inflating sizeof(struct FAM) by a whole pointer's worth of
 * bytes (16 instead of 4) it never actually reserves -- a silent
 * wrong-answer bug, not a crash, so it went unnoticed until measured
 * directly. See StructType#computeOffsets().
 */

struct FAM {
    int len;
    int data[];
};

/* A flexible member wider than the members before it still needs the
 * struct properly aligned/padded for it, even though it itself
 * contributes no size. */
struct Wide {
    char tag;
    double data[];
};

int
main(void)
{
    int n = 5;
    struct FAM *f = (struct FAM *)malloc(sizeof(struct FAM) + n * sizeof(int));
    f->len = n;
    for (int i = 0; i < n; i++) {
        f->data[i] = i * 10;
    }
    int sum = 0;
    for (int i = 0; i < f->len; i++) {
        sum += f->data[i];
    }
    printf("%d;%d;%d;%d;\n",
           (int)sizeof(struct FAM), f->len, sum, (int)sizeof(struct Wide));
    free(f);
    return 0;
}
