/* <inttypes.h> regression test: imaxabs/imaxdiv, strtoimax/strtoumax,
 * and a representative slice of the PRI* format macros used through
 * printf(). */
#include "stdio.h"
#include "inttypes.h"

int
main(void)
{
    printf("%" PRId64 ";", (int64_t)imaxabs(-42));
    imaxdiv_t d = imaxdiv(17, 5);
    printf("%" PRId64 ";%" PRId64 ";", (int64_t)d.quot, (int64_t)d.rem);

    printf("%" PRIdMAX ";", strtoimax("-123", NULL, 10));
    printf("%" PRIuMAX ";", strtoumax("456", NULL, 10));

    printf("%" PRId32 ";", (int32_t)100);
    printf("%" PRIu32 ";", (uint32_t)200);
    printf("%" PRIx32 ";", (uint32_t)255);

    printf("%" PRId8 ";", (int8_t)-5);
    printf("%" PRIu16 ";", (uint16_t)6000);
    return 0;
}
