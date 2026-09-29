/* <math.h> -- C99 7.12. Implemented in StandardRuntime.java on the JVM
 * backend (net.loveruby.cflat.sysdep.jvm.runtime, delegating to
 * java.lang.Math), and by the real libm on x86 (see GNULinker's "-lm").
 * Every double-taking function has a matching "f"-suffixed float
 * overload, exactly like real C99 -- this compiler has no function
 * overloading, so the two are simply two differently-named functions,
 * same as everywhere else a C library gives float/double a pair. */
#ifndef _CFLAT_MATH_H
#define _CFLAT_MATH_H

/* HUGE_VAL/HUGE_VALF/INFINITY/NAN need no library call at all: real
 * IEEE 754 double/float division already evaluates "1.0/0.0" to +Inf
 * and "0.0/0.0" to NaN at runtime, identically on both backends (a real
 * FPU on x86, the JVM's own double/float arithmetic on the JVM
 * backend), so these are just constant expressions, not functions. */
#define HUGE_VAL (1.0/0.0)
#define HUGE_VALF (1.0f/0.0f)
#define INFINITY (1.0/0.0)
#define NAN (0.0/0.0)

/* Common, near-universal (though not strictly C99) constants real
 * programs routinely expect from <math.h> -- POSIX (and long-standing
 * practice) rather than the C standard itself, which defines none of
 * these, but omitting them would just make every such program redefine
 * them by hand instead. */
#define M_E        2.7182818284590452354
#define M_LOG2E    1.4426950408889634074
#define M_LOG10E   0.43429448190325182765
#define M_LN2      0.69314718055994530942
#define M_LN10     2.30258509299404568402
#define M_PI       3.14159265358979323846
#define M_PI_2     1.57079632679489661923
#define M_PI_4     0.78539816339744830962
#define M_1_PI     0.31830988618379067154
#define M_2_PI     0.63661977236758134308
#define M_2_SQRTPI 1.12837916709551257390
#define M_SQRT2    1.41421356237309504880
#define M_SQRT1_2  0.70710678118654752440

extern double fabs(double x);
extern float fabsf(float x);
extern double sqrt(double x);
extern float sqrtf(float x);
extern double cbrt(double x);
extern float cbrtf(float x);
extern double pow(double x, double y);
extern float powf(float x, float y);
extern double exp(double x);
extern float expf(float x);
extern double exp2(double x);
extern float exp2f(float x);
extern double log(double x);
extern float logf(float x);
extern double log2(double x);
extern float log2f(float x);
extern double log10(double x);
extern float log10f(float x);
extern double sin(double x);
extern float sinf(float x);
extern double cos(double x);
extern float cosf(float x);
extern double tan(double x);
extern float tanf(float x);
extern double asin(double x);
extern float asinf(float x);
extern double acos(double x);
extern float acosf(float x);
extern double atan(double x);
extern float atanf(float x);
extern double atan2(double y, double x);
extern float atan2f(float y, float x);
extern double sinh(double x);
extern float sinhf(float x);
extern double cosh(double x);
extern float coshf(float x);
extern double tanh(double x);
extern float tanhf(float x);
extern double floor(double x);
extern float floorf(float x);
extern double ceil(double x);
extern float ceilf(float x);
extern double round(double x);
extern float roundf(float x);
extern double trunc(double x);
extern float truncf(float x);
extern double fmod(double x, double y);
extern float fmodf(float x, float y);
extern double hypot(double x, double y);
extern float hypotf(float x, float y);
extern double ldexp(double x, int exp);
extern float ldexpf(float x, int exp);
extern double copysign(double x, double y);
extern float copysignf(float x, float y);

/* Real C99 isnan()/isinf()/isfinite()/signbit() are type-generic
 * *macros* (float/double/long double all through one spelling) -- this
 * compiler has no type-generic macro mechanism, so these are plain
 * double-taking functions instead (a float argument promotes to double
 * at the call site, same as any other function call). */
extern int isnan(double x);
extern int isinf(double x);
extern int isfinite(double x);
extern int signbit(double x);

#endif
