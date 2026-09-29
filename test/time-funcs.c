/* <time.h> regression test: exercises calendar conversion (mktime <->
 * gmtime round trip, including weekday/yday), formatting (asctime,
 * strftime), difftime, and confirms time()/clock() return live,
 * reasonable values without depending on the exact current time. */
#include "stdio.h"
#include "time.h"

int
main(void)
{
    struct tm t;
    t.tm_year = 2024 - 1900;
    t.tm_mon = 3 - 1;
    t.tm_mday = 15;
    t.tm_hour = 12;
    t.tm_min = 30;
    t.tm_sec = 45;
    t.tm_isdst = 0;

    time_t epoch = mktime(&t);
    printf("%ld;", (long)epoch);
    printf("%d;%d;%d;", t.tm_wday, t.tm_yday, t.tm_year + 1900);

    struct tm* g = gmtime(&epoch);
    printf("%d-%02d-%02d %02d:%02d:%02d;",
            g->tm_year + 1900, g->tm_mon + 1, g->tm_mday,
            g->tm_hour, g->tm_min, g->tm_sec);

    char* a = asctime(g);
    a[24] = '\0';  /* asctime()'s fixed-width format is always exactly
                    * 24 chars + '\n' -- strip the '\n' for a clean,
                    * one-line-of-output test. */
    printf("%s;", a);

    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S %a %b", g);
    printf("%s;", buf);

    time_t e1 = 1000, e0 = 100;
    printf("%.1f;", difftime(e1, e0));

    time_t now = time(NULL);
    printf("%d;", (now > 1700000000L) ? 1 : 0);  /* sometime in 2023+ */

    clock_t c1 = clock();
    clock_t c2 = clock();
    printf("%d;", (c2 >= c1) ? 1 : 0);

    return 0;
}
