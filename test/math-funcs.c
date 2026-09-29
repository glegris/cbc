/* <math.h> regression test: exercises a representative slice of every
 * category the header declares -- double and float variants, the
 * HUGE_VAL/INFINITY/NAN constant-expression macros, and
 * isnan()/isinf()/isfinite()/signbit(). */
#include "stdio.h"
#include "math.h"

int
main(void)
{
    printf("%.4f;", sqrt(2.0));
    printf("%.4f;", sqrtf(2.0f));
    printf("%.4f;", pow(2.0, 10.0));
    printf("%.4f;", fabs(-3.5));
    printf("%.4f;", floor(3.7));
    printf("%.4f;", ceil(3.2));
    printf("%.4f;", round(2.5));
    printf("%.4f;", round(-2.5));
    printf("%.4f;", trunc(-3.9));
    printf("%.4f;", fmod(10.0, 3.0));
    printf("%.4f;", hypot(3.0, 4.0));
    printf("%.4f;", exp(1.0));
    printf("%.4f;", log(M_E));
    printf("%.4f;", log2(8.0));
    printf("%.4f;", log10(1000.0));
    printf("%.4f;", sin(0.0));
    printf("%.4f;", cos(0.0));
    printf("%.4f;", atan2(1.0, 1.0) * 4.0);
    printf("%d;", isnan(NAN));
    printf("%d;", isnan(1.0));
    printf("%d;", isinf(INFINITY));
    printf("%d;", isinf(1.0));
    printf("%d;", isfinite(1.0));
    printf("%d;", isfinite(HUGE_VAL));
    printf("%d;", signbit(-1.0));
    printf("%d;", signbit(1.0));
    return 0;
}
