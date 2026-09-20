#include "stdio.h"
#include "ctype.h"

int
main(int argc, char **argv)
{
    printf("%d;%d;", iscntrl('\n'), iscntrl('a'));
    printf("%d;%d;", isblank(' '), isblank('a'));
    printf("%d;%d;", isascii(200), isascii('a'));
    printf("%d;%d;", isprint('a'), isprint('\n'));
    printf("%d;%d;", isgraph('a'), isgraph(' '));
    printf("%d;", ispunct('.'));
    printf("%d\n", isxdigit('f'));
    return 0;
}
