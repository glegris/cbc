#include "stdio.h"

/* An empty statement (a bare ";", e.g. as a whole for/do-while loop
 * body, or just a stray ";") used to leave the parser's own "n"
 * variable as null instead of a real AST node, crashing the very
 * first visitor to walk it ("Cannot invoke StmtNode.accept because
 * <parameter1> is null") the moment one was ever reached. See
 * Parser.jj's stmt() rule.
 */

int
main(void)
{
    int i;
    int count = 0;
    for (i = 0; i < 5; i++)
        ;
    for (i = 0; i < 5; i++) {
        count++;
    }
    ;
    do
        ;
    while (0);
    printf("%d;%d;\n", count, i);
    return 0;
}
