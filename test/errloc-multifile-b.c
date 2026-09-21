/* Regression test for the JVM multi-file build's own #include-wrapper
 * mechanism (Compiler#buildMultipleSourcesAsOneUnit()): an error in the
 * *second* real source file used to be reported under the synthesized
 * wrapper file's own name/line, instead of this file's. */
extern int errloc_multifile_helper(void);

int
main(void)
{
    return errloc_multifile_undefined;
}
