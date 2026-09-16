package net.loveruby.cflat.sysdep.jvm.runtime;

import java.util.Arrays;

/**
 * Hand-written Java implementations of external functions callable from a
 * cflat program compiled with -arch=jvm, shared by every compiled program
 * (unlike NativeLibrary, generated fresh per program -- see its own
 * generated class doc). To make a function available everywhere without
 * regenerating anything, add a "public static" method here.
 *
 * Two call shapes exist, depending on how CodeGenerator reaches this class:
 *
 *  - The compile-time intrinsics (putchar/puts/printf's own building
 *    blocks below) are called directly, with whatever signature is most
 *    convenient -- the compiler already knows their exact names and emits
 *    calls to them by hand, so there's no need for a uniform convention.
 *
 *  - Everything else -- any function merely *declared* (e.g. via import)
 *    and called, not one of the three intrinsics above -- goes through
 *    the generic extern-call mechanism (CodeGenerator#compileNativeCall)
 *    and always receives this program's whole simulated address space as
 *    an implicit leading "byte[] mem" parameter, before its real cflat
 *    parameters (mapped the same way CodeGenerator#buildDescriptor maps
 *    a cflat signature: char/short/int/enum/_Bool -> JVM int, long/
 *    pointer -> JVM long, float/double -> their JVM equivalents). A
 *    pointer is a plain long byte-offset into mem; cast it with (int) to
 *    index mem directly (a JVM array can't be larger than 2^31 bytes
 *    anyway, so this never loses information for an address this backend
 *    could have handed out). Ignore the mem parameter in a method that
 *    has no need to read/write memory directly (abs, toupper, ...) --
 *    one uniform rule here needing no per-function metadata beats
 *    deciding case by case which functions need it.
 *
 * CodeGenerator keeps its own STANDARD_LIBRARY_FUNCTIONS set of names
 * already implemented here (a plain hardcoded list of names, deliberately
 * not found by reflecting over this class -- see that field's own doc
 * comment for why) so it knows to skip generating a NativeLibrary stub for
 * a name added here. Update that set too when adding a method.
 */
public class StandardLibrary {

    //
    // putchar/puts/printf's own building blocks -- called directly by
    // CodeGenerator (see compilePutchar/compilePuts/emitPrint*), not
    // through the generic extern-call mechanism, so no "mem" parameter.
    //

    public static int putchar(int c) {
        System.out.write(c);
        return c;
    }

    public static int puts(String s) {
        System.out.println(s);
        // Real libc puts() returns a non-negative count or EOF; tracking
        // the real count isn't worth it here, so always report success
        // (0) for whatever, rare, code actually looks at the result.
        return 0;
    }

    public static void printLiteral(String s) { System.out.print(s); }
    public static void printInt(int v) { System.out.print(v); }
    public static void printUnsignedInt(int v) { System.out.print(Integer.toUnsignedString(v)); }
    public static void printLong(long v) { System.out.print(v); }
    public static void printUnsignedLong(long v) { System.out.print(Long.toUnsignedString(v)); }
    public static void printChar(char c) { System.out.print(c); }
    public static void printString(String s) { System.out.print(s); }
    public static void printDouble(double d) { System.out.print(d); }

    //
    // <ctype.h> -- pure functions, "mem" unused.
    //

    public static int isdigit(byte[] mem, int c) {
        return (c >= '0' && c <= '9') ? 1 : 0;
    }

    public static int isalpha(byte[] mem, int c) {
        return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) ? 1 : 0;
    }

    public static int isalnum(byte[] mem, int c) {
        return (isalpha(mem, c) != 0 || isdigit(mem, c) != 0) ? 1 : 0;
    }

    public static int isspace(byte[] mem, int c) {
        return (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) ? 1 : 0;
    }

    public static int isupper(byte[] mem, int c) {
        return (c >= 'A' && c <= 'Z') ? 1 : 0;
    }

    public static int islower(byte[] mem, int c) {
        return (c >= 'a' && c <= 'z') ? 1 : 0;
    }

    public static int toupper(byte[] mem, int c) {
        return (c >= 'a' && c <= 'z') ? (c - 32) : c;
    }

    public static int tolower(byte[] mem, int c) {
        return (c >= 'A' && c <= 'Z') ? (c + 32) : c;
    }

    //
    // <string.h> -- operate on memory the caller already owns (no
    // allocation here: this backend has no general-purpose malloc/free
    // exposed to cflat code yet).
    //

    public static long strlen(byte[] mem, long s) {
        long len = 0;
        while (mem[at(s + len)] != 0) len++;
        return len;
    }

    public static long strcpy(byte[] mem, long dst, long src) {
        long i = 0;
        byte b;
        do {
            b = mem[at(src + i)];
            mem[at(dst + i)] = b;
            i++;
        } while (b != 0);
        return dst;
    }

    public static long strncpy(byte[] mem, long dst, long src, long n) {
        boolean ended = false;
        for (long i = 0; i < n; i++) {
            if (!ended) {
                byte b = mem[at(src + i)];
                mem[at(dst + i)] = b;
                ended = (b == 0);
            }
            else {
                mem[at(dst + i)] = 0;
            }
        }
        return dst;
    }

    public static long strcat(byte[] mem, long dst, long src) {
        strcpy(mem, dst + strlen(mem, dst), src);
        return dst;
    }

    public static long strncat(byte[] mem, long dst, long src, long n) {
        long dlen = strlen(mem, dst);
        long i = 0;
        while (i < n) {
            byte b = mem[at(src + i)];
            if (b == 0) break;
            mem[at(dst + dlen + i)] = b;
            i++;
        }
        mem[at(dst + dlen + i)] = 0;
        return dst;
    }

    public static int strcmp(byte[] mem, long a, long b) {
        long i = 0;
        while (true) {
            int ca = ubyte(mem, a + i);
            int cb = ubyte(mem, b + i);
            if (ca != cb) return ca - cb;
            if (ca == 0) return 0;
            i++;
        }
    }

    public static int strncmp(byte[] mem, long a, long b, long n) {
        for (long i = 0; i < n; i++) {
            int ca = ubyte(mem, a + i);
            int cb = ubyte(mem, b + i);
            if (ca != cb) return ca - cb;
            if (ca == 0) return 0;
        }
        return 0;
    }

    public static long strchr(byte[] mem, long s, int c) {
        byte target = (byte) c;
        long i = 0;
        while (true) {
            byte b = mem[at(s + i)];
            if (b == target) return s + i;
            if (b == 0) return 0;
            i++;
        }
    }

    public static long memcpy(byte[] mem, long dst, long src, long n) {
        System.arraycopy(mem, at(src), mem, at(dst), at(n));
        return dst;
    }

    public static long memmove(byte[] mem, long dst, long src, long n) {
        System.arraycopy(mem, at(src), mem, at(dst), at(n));  // already overlap-safe
        return dst;
    }

    public static long memset(byte[] mem, long dst, int c, long n) {
        Arrays.fill(mem, at(dst), at(dst + n), (byte) c);
        return dst;
    }

    public static int memcmp(byte[] mem, long a, long b, long n) {
        for (long i = 0; i < n; i++) {
            int ca = ubyte(mem, a + i);
            int cb = ubyte(mem, b + i);
            if (ca != cb) return ca - cb;
        }
        return 0;
    }

    //
    // <stdlib.h> -- numeric conversions/abs, the parts that don't need a
    // heap allocator.
    //

    public static int abs(byte[] mem, int x) {
        return Math.abs(x);
    }

    public static long labs(byte[] mem, long x) {
        return Math.abs(x);
    }

    public static int atoi(byte[] mem, long s) {
        return (int) atolValue(mem, s);
    }

    public static long atol(byte[] mem, long s) {
        return atolValue(mem, s);
    }

    public static double atof(byte[] mem, long s) {
        StringBuilder sb = new StringBuilder();
        long i = skipSpaces(mem, s);
        int c = ubyte(mem, i);
        if (c == '+' || c == '-') {
            sb.append((char) c);
            i++;
        }
        boolean sawDigitOrDot = false;
        while (true) {
            c = ubyte(mem, i);
            if ((c >= '0' && c <= '9') || c == '.') {
                sawDigitOrDot = true;
                sb.append((char) c);
                i++;
            }
            else {
                break;
            }
        }
        if ((c == 'e' || c == 'E') && sawDigitOrDot) {
            sb.append((char) c);
            i++;
            c = ubyte(mem, i);
            if (c == '+' || c == '-') {
                sb.append((char) c);
                i++;
            }
            while (ubyte(mem, i) >= '0' && ubyte(mem, i) <= '9') {
                sb.append((char) ubyte(mem, i));
                i++;
            }
        }
        if (!sawDigitOrDot || sb.length() == 0) {
            return 0.0;
        }
        try {
            return Double.parseDouble(sb.toString());
        }
        catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    //
    // Internal helpers -- not callable from cflat code.
    //

    private static int at(long addr) {
        return (int) addr;
    }

    private static int ubyte(byte[] mem, long addr) {
        return mem[at(addr)] & 0xFF;
    }

    private static long skipSpaces(byte[] mem, long s) {
        long i = s;
        while (isspace(mem, ubyte(mem, i)) != 0) i++;
        return i;
    }

    private static long atolValue(byte[] mem, long s) {
        long i = skipSpaces(mem, s);
        boolean neg = false;
        int c = ubyte(mem, i);
        if (c == '+' || c == '-') {
            neg = (c == '-');
            i++;
        }
        long value = 0;
        while (true) {
            c = ubyte(mem, i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            i++;
        }
        return neg ? -value : value;
    }
}
