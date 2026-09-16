/* <stdbool.h> -- C99 7.16. Real C99 makes bool/true/false macros, not
 * keywords (_Bool is the actual keyword), so this is a #include, not an
 * "import": those go through the preprocessor and expand textually in
 * whatever file includes them, exactly like a real libc's stdbool.h;
 * "import" instead loads a compiled declaration file and shares no
 * macros with the importing file at all, so a "bool"/"true"/"false"
 * defined there would never be visible here.
 *
 * Usage: #include "stdbool.h" (search it like any other include, e.g.
 * with -I pointing at this "import" directory).
 */
#ifndef __STDBOOL_H
#define __STDBOOL_H

#define bool _Bool
#define true 1
#define false 0
#define __bool_true_false_are_defined 1

#endif
