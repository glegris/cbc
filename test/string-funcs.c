#include "stdio.h"
#include "string.h"

int
main(int argc, char **argv)
{
    printf("%c;", *strrchr("hello world", 'o'));
    printf("%d;", (int)strspn("abc123", "abc"));
    printf("%d;", (int)strcspn("abc123", "123"));
    printf("%s;", strpbrk("hello world", "ow"));
    printf("%s;", strstr("hello world", "wor"));
    printf("%d;", (int)strnlen("hello", 3));
    printf("%d;", strcoll("abc", "abd") < 0);

    char[32] buf;
    strxfrm(buf, "copy me", 32);
    printf("%s;", buf);

    char *dup = strdup("dup me");
    printf("%s;", dup);
    free(dup);

    char *ndup = strndup("truncated", 4);
    printf("%s;", ndup);
    free(ndup);

    char[8] s;
    strcpy(s, "a,b,,c");
    char *tok = strtok(s, ",");
    while (tok != NULL) {
        printf("%s.", tok);
        tok = strtok(NULL, ",");
    }
    printf(";");

    unsigned char[5] data = {1, 2, 3, 4, 5};
    unsigned char[5] dest;
    void *r = memccpy(dest, data, 3, 5);
    printf("%d;%d\n", r != NULL, dest[2]);
    return 0;
}
