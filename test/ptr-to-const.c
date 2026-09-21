/* "const char *p" means "p points to a const char" (the pointee can't
 * be written through p, but p itself stays freely reassignable) -- NOT
 * "p itself is a const pointer to mutable char". declaratorType() used
 * to wrap the *whole*, already-pointer-ified declarator type in
 * QualifiedTypeRef, getting this backwards for every "T *p"-shaped
 * const/volatile declaration; qualifyBase() now pushes the qualifier
 * down through however many "*"/"[]" layers wrap the base type instead,
 * found via stb_image.h's own reassigned "static const char
 * *stbi__g_failure_reason;". */
#include "stdio.h"

static const char *reason;

static int
fail(const char *msg)
{
    reason = msg;
    return 0;
}

int
main(void)
{
    fail("first");
    printf("%s;", reason);
    fail("second");
    printf("%s;", reason);
    return 0;
}
