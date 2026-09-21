import net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime;

/** Companion Java harness for pubapi-runtime.c (see its own comment):
 *  exercises the JVM backend's public runtime API (StandardRuntime's
 *  "Public Java API" section) both ways -- a value plain Java writes is
 *  read back correctly by compiled C code, and a value compiled C code
 *  wrote is read back correctly by plain Java -- not just each side's
 *  own, necessarily self-consistent view of memory. No subprocess, no
 *  reflection: "pubapi_runtime.$runtime()" and every function this
 *  calls (globalIntAddr(), readGlobalBuf(), ...) are already public
 *  static methods on the compiled class, called directly. */
public class PubapiRuntimeTest {
    public static void main(String[] args) throws Exception {
        StandardRuntime rt = pubapi_runtime.$runtime();

        long intAddr = pubapi_runtime.globalIntAddr();
        System.out.print(rt.readInt(intAddr) + ";");        // sees C's own initializer
        rt.writeInt(intAddr, 222);
        System.out.print(pubapi_runtime.readGlobalInt() + ";"); // C sees Java's write

        long bufAddr = pubapi_runtime.globalBufAddr();
        System.out.print(rt.readCString(bufAddr) + ";");     // sees C's own initializer
        byte[] replacement = "REPLACED".getBytes("UTF-8");
        rt.writeBytes(bufAddr, replacement);
        rt.writeByte(bufAddr + replacement.length, 0);
        long bufAddrFromC = pubapi_runtime.readGlobalBuf(); // char* return -> long address
        System.out.print(rt.readCString(bufAddrFromC) + ";"); // C sees Java's write too

        // Non-ASCII, to exercise UTF-8 round-tripping -- compared by
        // bytes, not printed as text: the console/shell capturing this
        // test's own stdout may not itself be UTF-8, which would make
        // an otherwise-correct result look wrong (or vice versa) for a
        // reason that has nothing to do with this API.
        long newAddr = rt.newString("héllo");
        byte[] utf8 = "héllo".getBytes("UTF-8");
        System.out.print(rt.readCString(newAddr).equals("héllo") + ";");
        System.out.print(java.util.Arrays.equals(rt.readBytes(newAddr, utf8.length), utf8) + ";");

        System.out.println(rt.memory() == rt.memory());     // same backing array both times
    }
}
