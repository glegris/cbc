#include "stdio.h"
#include "string.h"

int
main(int argc, char **argv)
{
    char[64] buf;
    char[16] fmt;
    int n;
    int x;

    /* runtime (non-literal) format string, not just a compile-time literal */
    fmt[0] = '%'; fmt[1] = 'd'; fmt[2] = '-'; fmt[3] = '%'; fmt[4] = 's'; fmt[5] = 0;
    printf(fmt, 7, "seven");
    printf(";");

    /* width/flags/precision */
    printf("[%5d][%-5d][%05d][%+d][%+d];", 42, 42, 42, 42, -42);
    printf("[%-10s][%.2s];", "hi", "hello");

    /* unsigned/hex/octal, including "l" length modifier -- the "%ld"
     * value is kept within 32-bit signed range so this test's expected
     * output is identical on both backends despite x86's 32-bit "long"
     * vs. the JVM backend's 64-bit one (see sizeof-type/sizeof-expr's
     * own KNOWN_DIFF entries in run_jvm.sh for that difference). */
    printf("[%u][%x][%X][%#x][%o][%ld];", 4000000000UL, 255, 255, 255, 8, 123456789L);

    /* %f with default/explicit precision and width */
    printf("[%f][%.2f][%8.2f];", 3.14159, 3.14159, 3.14159);

    /* literal %% */
    printf("100%%;");

    /* sprintf/snprintf, including truncation (count smaller than the
     * full formatted length -- the return value is still the length
     * that WOULD have been written, per C99, not the truncated one) */
    n = sprintf(buf, "x=%d,y=%s", 5, "abc");
    printf("%s,%d;", buf, n);
    n = snprintf(buf, 3, "%d", 12345);
    printf("%s,%d;", buf, n);

    /* %p: just check it looks like "0x..." -- the actual address isn't
     * deterministic, so this can't be compared against a fixed string */
    x = 0;
    sprintf(buf, "%p", (void*)&x);
    printf("%d\n", buf[0] == '0' && buf[1] == 'x');
    return 0;
}
