#include "stdio.h"

/* A local (or global/typedef/struct-member/parameter) variable's own
 * "[N]"/"[]" array suffix attaches to the *name*, not to the type
 * keyword before it -- e.g. "int arr[2];" -- but every declaration
 * production here used to just parse "type() name()" with nothing
 * consuming a trailing "[...]" at all, an immediate parse error the
 * moment any array-typed declarator was used outside of a typeref()
 * context (a parameter/cast/sizeof spelling). Found via the
 * c-testsuite project's own tests. See arraySuffix()/withArrayDims().
 */

struct Point {
    int coords[2];
};

typedef int IntPair[2];

int
sum3(int nums[3])
{
    return nums[0] + nums[1] + nums[2];
}

int
main(void)
{
    int arr[2];
    int matrix[2][3];
    IntPair pair;
    struct Point pt;
    int three[3];

    arr[0] = 1;
    arr[1] = 2;

    matrix[0][0] = 1;
    matrix[0][1] = 2;
    matrix[0][2] = 3;
    matrix[1][0] = 4;
    matrix[1][1] = 5;
    matrix[1][2] = 6;

    pair[0] = 10;
    pair[1] = 20;

    pt.coords[0] = 7;
    pt.coords[1] = 8;

    three[0] = 1;
    three[1] = 2;
    three[2] = 3;

    printf("%d;%d;%d;%d;%d;%d;\n",
           arr[0] + arr[1],
           matrix[0][2] + matrix[1][0],
           pair[0] + pair[1],
           pt.coords[0] + pt.coords[1],
           sum3(three),
           (int)sizeof(arr));
    return 0;
}
