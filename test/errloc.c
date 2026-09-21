/* Regression test for Preprocessor's LineMap / Parser#location(): a
 * compile error inside an #include'd file (errloc.h, here) used to
 * always be reported under this top-level file's own name, at some raw
 * line count within Preprocessor's internal flattened buffer -- instead
 * of the file and line that actually has the mistake. See run_jvm.sh's
 * assert_error_contains for what this checks. */
#include "errloc.h"

int
main(void)
{
    return errloc_undefined_after_include;
}
