/* Regression test for the JVM backend's public Java-facing runtime API
 * (StandardRuntime's "Public Java API" section, and CodeGenerator's
 * generated "$runtime()" accessor): plain Java code sharing the same
 * JVM as a compiled cflat program can read/write its simulated memory
 * directly -- decode.$runtime().readInt(addr), etc. -- with no
 * subprocess and no reflection. See PubapiRuntimeTest.java, this
 * file's own companion harness, for the actual interop checks (a value
 * Java writes is read back correctly by compiled C code, and vice
 * versa); this file just exposes what that harness needs: an address
 * to read/write at, and a way to read the result back from the C side
 * to confirm Java's own write actually took effect there too, not just
 * in Java's own (necessarily self-consistent) view of memory. */
int global_int = 111;
char global_buf[32] = {'i', 'n', 'i', 't', 'i', 'a', 'l'};

long
globalIntAddr(void)
{
    return (long)&global_int;
}

long
globalBufAddr(void)
{
    return (long)global_buf;
}

int
readGlobalInt(void)
{
    return global_int;
}

char *
readGlobalBuf(void)
{
    return global_buf;
}

int
main(void)
{
    return 0;
}
