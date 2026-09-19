/* <stdbool.h> -- C99 7.16. Real C99 makes bool/true/false macros, not
 * keywords (_Bool is the actual keyword). Usage: #include "stdbool.h"
 * (searched on the same -I path as any other header). */
#ifndef __STDBOOL_H
#define __STDBOOL_H

#define bool _Bool
#define true 1
#define false 0
#define __bool_true_false_are_defined 1

#endif
