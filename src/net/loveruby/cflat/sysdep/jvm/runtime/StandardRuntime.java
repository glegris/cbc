package net.loveruby.cflat.sysdep.jvm.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Hand-written Java implementations of external functions callable from a
 * cflat program compiled with -arch=jvm, shared by every compiled program
 * (unlike NativeRuntime, generated fresh per program -- see its own
 * generated class doc). To make a function available everywhere without
 * regenerating anything, add a "public" instance method here.
 *
 * This class owns and allocates this program's whole simulated address
 * space -- the "mem" field below -- itself: the compiled program class
 * extends NativeRuntime, which extends this class directly (see
 * CodeGenerator's own class doc), and its one instance, constructed once
 * in the generated class's <clinit> and stored in its "$rt" static
 * field, is what every read/write of simulated memory ultimately goes
 * through. A method that needs to read/write memory (strlen, strcpy,
 * ...) just uses "mem" directly, and one that doesn't (abs, toupper,
 * ...) simply never mentions it. A cflat pointer is a plain long
 * byte-offset into mem; cast it with (int) to index mem directly (a JVM
 * array can't be larger than 2^31 bytes anyway, so this never loses
 * information for an address this backend could have handed out).
 *
 * The generated program class itself stays fully static, exactly as
 * before this existed -- only calls *into* this runtime go through an
 * instance, via the one shared "$rt".
 *
 * Two call shapes exist, depending on how CodeGenerator reaches a method:
 *
 *  - The compile-time intrinsics (putchar/puts/printf's own building
 *    blocks below) are called directly, with whatever signature is most
 *    convenient -- the compiler already knows their exact names and emits
 *    calls to them by hand.
 *
 *  - Everything else -- any function merely *declared* (e.g. via import)
 *    and called, not one of the three intrinsics above -- goes through
 *    the generic extern-call mechanism (CodeGenerator#compileNativeCall),
 *    with parameters/return type mapped the same way
 *    CodeGenerator#buildDescriptor maps a cflat signature: char/short/
 *    int/enum/_Bool -> JVM int, long/pointer -> JVM long, float/double ->
 *    their JVM equivalents.
 *
 * CodeGenerator keeps its own STANDARD_RUNTIME_FUNCTIONS set of names
 * already implemented here (a plain hardcoded list of names, deliberately
 * not found by reflecting over this class -- see that field's own doc
 * comment for why) so it knows to skip generating a NativeRuntime stub for
 * a name added here. Update that set too when adding a method.
 */
public class StandardRuntime {
    /** The compiled program's whole simulated address space, in bytes.
     *  CodeGenerator reads this constant directly (a real Java compile-
     *  time constant, inlined into CodeGenerator.class when it's built,
     *  since both are compiled together -- see bin/build.sh) wherever it
     *  needs to know the size, e.g. the stack pointer's initial value. */
    public static final int HEAP_SIZE = 8 * 1024 * 1024;  // 8MB

    protected final byte[] mem;

    /** Typed little-endian access over the same array as "mem" (matching
     *  the generated class's own "$buf", which wraps this identical
     *  array reference) -- used by va_next() below for a plain 8-byte
     *  read/increment, sparing it manual byte-shifting. */
    private final ByteBuffer buf;

    public StandardRuntime() {
        this.mem = new byte[HEAP_SIZE];
        this.buf = ByteBuffer.wrap(mem).order(ByteOrder.LITTLE_ENDIAN);
    }

    //
    // putchar/puts/printf's own building blocks -- called directly by
    // CodeGenerator (see compilePutchar/compilePuts/emitPrint*), not
    // through the generic extern-call mechanism.
    //

    public int putchar(int c) {
        System.out.write(c);
        return c;
    }

    public int puts(String s) {
        System.out.println(s);
        // Real libc puts() returns a non-negative count or EOF; tracking
        // the real count isn't worth it here, so always report success
        // (0) for whatever, rare, code actually looks at the result.
        return 0;
    }

    public void printLiteral(String s) { System.out.print(s); }
    public void printInt(int v) { System.out.print(v); }
    public void printUnsignedInt(int v) { System.out.print(Integer.toUnsignedString(v)); }
    public void printLong(long v) { System.out.print(v); }
    public void printUnsignedLong(long v) { System.out.print(Long.toUnsignedString(v)); }
    public void printChar(char c) { System.out.print(c); }
    public void printString(String s) { System.out.print(s); }
    public void printDouble(double d) { System.out.print(d); }

    //
    // <ctype.h> -- pure functions, "mem" unused.
    //

    public int isdigit(int c) {
        return (c >= '0' && c <= '9') ? 1 : 0;
    }

    public int isalpha(int c) {
        return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) ? 1 : 0;
    }

    public int isalnum(int c) {
        return (isalpha(c) != 0 || isdigit(c) != 0) ? 1 : 0;
    }

    public int isspace(int c) {
        return (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) ? 1 : 0;
    }

    public int isupper(int c) {
        return (c >= 'A' && c <= 'Z') ? 1 : 0;
    }

    public int islower(int c) {
        return (c >= 'a' && c <= 'z') ? 1 : 0;
    }

    public int toupper(int c) {
        return (c >= 'a' && c <= 'z') ? (c - 32) : c;
    }

    public int tolower(int c) {
        return (c >= 'A' && c <= 'Z') ? (c + 32) : c;
    }

    //
    // <string.h> -- operate on memory the caller already owns (no
    // allocation here: this backend has no general-purpose malloc/free
    // exposed to cflat code yet).
    //

    public long strlen(long s) {
        long len = 0;
        while (mem[at(s + len)] != 0) len++;
        return len;
    }

    public long strcpy(long dst, long src) {
        long i = 0;
        byte b;
        do {
            b = mem[at(src + i)];
            mem[at(dst + i)] = b;
            i++;
        } while (b != 0);
        return dst;
    }

    public long strncpy(long dst, long src, long n) {
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

    public long strcat(long dst, long src) {
        strcpy(dst + strlen(dst), src);
        return dst;
    }

    public long strncat(long dst, long src, long n) {
        long dlen = strlen(dst);
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

    public int strcmp(long a, long b) {
        long i = 0;
        while (true) {
            int ca = ubyte(a + i);
            int cb = ubyte(b + i);
            if (ca != cb) return ca - cb;
            if (ca == 0) return 0;
            i++;
        }
    }

    public int strncmp(long a, long b, long n) {
        for (long i = 0; i < n; i++) {
            int ca = ubyte(a + i);
            int cb = ubyte(b + i);
            if (ca != cb) return ca - cb;
            if (ca == 0) return 0;
        }
        return 0;
    }

    public long strchr(long s, int c) {
        byte target = (byte) c;
        long i = 0;
        while (true) {
            byte b = mem[at(s + i)];
            if (b == target) return s + i;
            if (b == 0) return 0;
            i++;
        }
    }

    public long memcpy(long dst, long src, long n) {
        System.arraycopy(mem, at(src), mem, at(dst), at(n));
        return dst;
    }

    public long memmove(long dst, long src, long n) {
        System.arraycopy(mem, at(src), mem, at(dst), at(n));  // already overlap-safe
        return dst;
    }

    public long memset(long dst, int c, long n) {
        Arrays.fill(mem, at(dst), at(dst + n), (byte) c);
        return dst;
    }

    public int memcmp(long a, long b, long n) {
        for (long i = 0; i < n; i++) {
            int ca = ubyte(a + i);
            int cb = ubyte(b + i);
            if (ca != cb) return ca - cb;
        }
        return 0;
    }

    //
    // <stdlib.h> -- numeric conversions/abs, the parts that don't need a
    // heap allocator.
    //

    public int abs(int x) {
        return Math.abs(x);
    }

    public long labs(long x) {
        return Math.abs(x);
    }

    public int atoi(long s) {
        return (int) atolValue(s);
    }

    public long atol(long s) {
        return atolValue(s);
    }

    public double atof(long s) {
        StringBuilder sb = new StringBuilder();
        long i = skipSpaces(s);
        int c = ubyte(i);
        if (c == '+' || c == '-') {
            sb.append((char) c);
            i++;
        }
        boolean sawDigitOrDot = false;
        while (true) {
            c = ubyte(i);
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
            c = ubyte(i);
            if (c == '+' || c == '-') {
                sb.append((char) c);
                i++;
            }
            while (ubyte(i) >= '0' && ubyte(i) <= '9') {
                sb.append((char) ubyte(i));
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
    // <stdarg.h> -- va_next() is the only half of cflat's va_list support
    // (see lib/stdarg.cb) that needs a JVM-specific implementation:
    // va_init() itself is a CodeGenerator compile-time intrinsic (see
    // compileVaInit), since only the compiler knows where a given
    // function's "..." tail was marshalled to (see compileVarargTail) --
    // but once va_init() has handed back that address, walking forward
    // through it one 8-byte slot at a time is just ordinary pointer
    // arithmetic, identical in spirit to lib/stdarg.cb's own x86
    // implementation ("va_arg_t arg = **ap; (*ap)++; return arg;"), just
    // expressed here directly against "buf" instead of relying on cflat
    // pointer dereference codegen.
    //

    public long va_next(long apAddr) {
        int a = at(apAddr);
        long valueAddr = buf.getLong(a);
        long value = buf.getLong(at(valueAddr));
        buf.putLong(a, valueAddr + 8);
        return value;
    }

    //
    // Internal helpers -- not callable from cflat code.
    //

    private static int at(long addr) {
        return (int) addr;
    }

    private int ubyte(long addr) {
        return mem[at(addr)] & 0xFF;
    }

    private long skipSpaces(long s) {
        long i = s;
        while (isspace(ubyte(i)) != 0) i++;
        return i;
    }

    private long atolValue(long s) {
        long i = skipSpaces(s);
        boolean neg = false;
        int c = ubyte(i);
        if (c == '+' || c == '-') {
            neg = (c == '-');
            i++;
        }
        long value = 0;
        while (true) {
            c = ubyte(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            i++;
        }
        return neg ? -value : value;
    }
}
