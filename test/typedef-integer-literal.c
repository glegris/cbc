/* isSafeIntegerCast() used to unconditionally cast a type known only to
 * be "some integer type" (Type#isInteger()) straight to IntegerType,
 * crashing (ClassCastException) whenever that type was actually a
 * UserType/QualifiedType *wrapping* one instead of literally being one
 * -- e.g. any integer literal initializing a variable of a typedef'd
 * integer type, like stb_image.h's own "typedef unsigned char
 * stbi_uc;". getIntegerType() (already the established, polymorphic way
 * every other part of this compiler unwraps such a type) fixes it. */
#include "stdio.h"

typedef unsigned char myuc;

int
main(void)
{
    myuc c = 200;
    printf("%d;", c);
    return 0;
}
