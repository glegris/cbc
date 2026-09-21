#include "stdio.h"

int
main(int argc, char **argv)
{
    FILE *f = fopen("file-io-test.tmp", "w");
    if (f == NULL) {
        printf("open-write failed\n");
        return 1;
    }
    fputs("hello file\n", f);
    fputc('X', f);
    fwrite("world", 1, 5, f);
    fclose(f);

    f = fopen("file-io-test.tmp", "r");
    if (f == NULL) {
        printf("open-read failed\n");
        return 1;
    }
    char[64] buf;
    char *line = fgets(buf, 64, f);
    printf("%s", line);
    printf("%c", fgetc(f));

    char[16] rest;
    size_t n = fread(rest, 1, 5, f);
    rest[n] = '\0';
    printf("%s;", rest);
    printf("%d;", feof(f));
    fgetc(f);  /* one more read past the end -- actually hits EOF */
    printf("%d;", feof(f));

    fseek(f, 0, SEEK_SET);
    printf("%d;", (int)ftell(f));
    fseek(f, 2, SEEK_CUR);
    printf("%d;", (int)ftell(f));
    fseek(f, 0, SEEK_END);
    printf("%d;", (int)ftell(f));
    fclose(f);

    /* stdin/stdout/stderr are real, addressable objects now, not a
     * bare extern declaration with nothing behind it */
    printf("%d\n", &stdin != NULL && &stdout != NULL && &stderr != NULL);
    return 0;
}
