package net.loveruby.cflat.sysdep.jvm.runtime;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

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
 *  - Everything else -- any function merely *declared* (e.g. via #include)
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
    // <stdlib.h> malloc()/calloc()/realloc()/free(): a first-fit free-list
    // allocator carved out of the same "mem" array everything else already
    // uses, starting right where the static-data area (globals/string
    // literals) ends -- see mir_init_heap(), called once from
    // CodeGenerator's generated <clinit>, before a single byte of dynamic
    // memory (including argv, which is built afterwards, in the generated
    // main() bridge) has been handed out. This is also the ONE allocator
    // for every dynamic allocation the JVM backend itself ever needs
    // internally (a vararg call's marshalled "..." block, a by-value
    // struct/union copy's scratch buffer, argv's own strings/pointer
    // array -- see CodeGenerator's "$alloc"/emitAllocHelper, which is
    // just a thin wrapper around malloc() below) as well as for the
    // compiled program's own malloc()/calloc()/realloc() calls: there
    // used to be a second, independent bump pointer for the compiler's
    // own internal allocations, started from this exact same address,
    // which could -- and once actually did -- race this one and hand out
    // overlapping memory. Unlike glibc's malloc, this can never grow
    // "mem" past HEAP_SIZE (a real byte[] can't be resized in place), so
    // running out of heap space returns NULL, same as any other
    // conforming malloc is allowed to do under real memory pressure --
    // there's no separate "heap exhausted" case to handle.
    //

    private static final class Block {
        long size;
        boolean free;
        Block(long size, boolean free) { this.size = size; this.free = free; }
    }

    /** address -> block, for every address malloc() has ever handed out
     *  (whether currently free or still live) -- the unallocated tail
     *  from heapNext to the end of "mem" isn't represented here at all;
     *  malloc() only consults this map for a reusable free block, falling
     *  back to carving a fresh one off heapNext. */
    private final TreeMap<Long, Block> heapBlocks = new TreeMap<Long, Block>();
    private long heapNext = -1;  // -1 until mir_init_heap() runs

    /** Called exactly once, from CodeGenerator's generated <clinit> --
     *  before anything else in the compiled program/backend has run --
     *  with start set to right where the static-data area
     *  (computeStaticLayout's own globals/string-literal layout) ends. */
    public void mir_init_heap(long start) {
        heapNext = start;
        heapBlocks.clear();
    }

    public long malloc(long size) {
        if (size <= 0) {
            return 0;
        }
        // 8-byte alignment, matching real malloc's guarantee that the
        // returned address is suitably aligned for this backend's widest
        // scalar (long/double/any pointer).
        long asize = (size + 7L) & ~7L;
        for (Map.Entry<Long, Block> e : heapBlocks.entrySet()) {
            Block b = e.getValue();
            if (b.free && b.size >= asize) {
                long leftover = b.size - asize;
                b.free = false;
                if (leftover > 0) {
                    b.size = asize;
                    heapBlocks.put(e.getKey() + asize, new Block(leftover, true));
                }
                return e.getKey();
            }
        }
        if (heapNext + asize > mem.length) {
            return 0;  // out of memory -- see this section's own doc comment
        }
        long addr = heapNext;
        heapBlocks.put(addr, new Block(asize, false));
        heapNext += asize;
        return addr;
    }

    public long calloc(long nmemb, long size) {
        long total = nmemb * size;
        long addr = malloc(total);
        if (addr != 0) {
            Arrays.fill(mem, at(addr), at(addr + total), (byte) 0);
        }
        return addr;
    }

    public long realloc(long addr, long size) {
        if (addr == 0) {
            return malloc(size);
        }
        if (size == 0) {
            free(addr);
            return 0;
        }
        Block b = heapBlocks.get(addr);
        if (b == null) {
            return 0;  // not a pointer malloc() ever gave out -- real realloc() is UB here too
        }
        if (b.size >= size) {
            return addr;  // already big enough; real realloc() is free to keep it as-is
        }
        long newAddr = malloc(size);
        if (newAddr == 0) {
            return 0;
        }
        System.arraycopy(mem, at(addr), mem, at(newAddr), at(b.size));
        free(addr);
        return newAddr;
    }

    public void free(long addr) {
        if (addr == 0) {
            return;
        }
        Block b = heapBlocks.get(addr);
        if (b == null) {
            return;  // freeing a pointer malloc() never gave out is UB; just ignore it
        }
        b.free = true;
        // Merge with the immediately-following block if it's also free,
        // so a long malloc/free churn doesn't fragment the heap into ever
        //-smaller unusable pieces. Not merged backward with a preceding
        // free block -- finding it would need a second lookup this simple
        // a scheme doesn't bother with; forward-only merging still keeps
        // fragmentation bounded for the common allocate/free/reallocate
        // patterns real programs use.
        Long nextKey = heapBlocks.higherKey(addr);
        if (nextKey != null && nextKey == addr + b.size) {
            Block next = heapBlocks.get(nextKey);
            if (next.free) {
                b.size += next.size;
                heapBlocks.remove(nextKey);
            }
        }
    }

    //
    // <stdlib.h> -- numeric conversions/abs/exit, the parts that don't
    // need a heap allocator.
    //

    public void exit(int status) {
        System.exit(status);
    }

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
    // File I/O primitives backing <stdio.h>'s FILE*-taking functions
    // (see that header for FILE's own definition -- a plain fd wrapper
    // here, this backend's translation of the real OS-level concept).
    // fd 0/1/2 are reserved for stdin/stdout/stderr, going through the
    // JVM's own System.in/out/err (the same streams putchar/puts/printf
    // already use above, not a separate RandomAccessFile) -- everything
    // mir_sysio_open() successfully opens gets a real one instead, keyed
    // by an incrementing fd starting at 3.
    //

    private final Map<Integer, RandomAccessFile> fdTable = new HashMap<Integer, RandomAccessFile>();
    private final Map<Integer, Boolean> fdEof = new HashMap<Integer, Boolean>();
    private int nextFd = 3;

    /** Opens "mode" ("r"/"w"/"a", each optionally with "+"/"b") the same
     *  way fopen() promises to, returning a new fd (>= 3) on success or
     *  -1 on failure -- there's no errno to report a real cause through
     *  (see <errno.h>'s own doc comment), so every failure just looks
     *  like ENOENT to the caller. */
    public int mir_sysio_open(long pathAddr, long modeAddr) {
        String path = readCString(pathAddr);
        String mode = readCString(modeAddr);
        try {
            String rafMode = "r";
            boolean append = false;
            if (mode.indexOf('w') >= 0 || mode.indexOf('+') >= 0) {
                rafMode = "rw";
            }
            if (mode.indexOf('a') >= 0) {
                rafMode = "rw";
                append = true;
            }
            RandomAccessFile raf = new RandomAccessFile(path, rafMode);
            if (mode.indexOf('w') >= 0) {
                raf.setLength(0L);
            }
            raf.seek(append ? raf.length() : 0L);
            int fd = nextFd++;
            fdTable.put(fd, raf);
            fdEof.put(fd, Boolean.FALSE);
            return fd;
        }
        catch (IOException e) {
            return -1;
        }
    }

    public int mir_sysio_close(int fd) {
        if (fd == 0 || fd == 1 || fd == 2) {
            return 0;  // never really closed, same as real libc's std streams
        }
        RandomAccessFile raf = fdTable.remove(fd);
        fdEof.remove(fd);
        if (raf == null) {
            return -1;
        }
        try {
            raf.close();
            return 0;
        }
        catch (IOException e) {
            return -1;
        }
    }

    public long mir_sysio_read(int fd, long bufAddr, long count) {
        if (count <= 0) {
            return 0;
        }
        if (fd == 1 || fd == 2) {
            return -1;  // stdout/stderr aren't readable
        }
        byte[] tmp = new byte[(int) count];
        try {
            int got;
            if (fd == 0) {
                got = System.in.read(tmp);
            }
            else {
                RandomAccessFile raf = fdTable.get(fd);
                if (raf == null) return -1;
                got = raf.read(tmp);
            }
            if (got <= 0) {
                fdEof.put(fd, Boolean.TRUE);
                return 0;
            }
            System.arraycopy(tmp, 0, mem, at(bufAddr), got);
            return got;
        }
        catch (IOException e) {
            return -1;
        }
    }

    public long mir_sysio_write(int fd, long bufAddr, long count) {
        if (count <= 0) {
            return 0;
        }
        byte[] tmp = new byte[(int) count];
        System.arraycopy(mem, at(bufAddr), tmp, 0, (int) count);
        try {
            if (fd == 1) {
                System.out.write(tmp);
                System.out.flush();
                return count;
            }
            if (fd == 2) {
                System.err.write(tmp);
                System.err.flush();
                return count;
            }
            if (fd == 0) {
                return -1;  // stdin isn't writable
            }
            RandomAccessFile raf = fdTable.get(fd);
            if (raf == null) return -1;
            raf.write(tmp);
            return count;
        }
        catch (IOException e) {
            return -1;
        }
    }

    public long mir_sysio_seek(int fd, long offset, int whence) {
        RandomAccessFile raf = fdTable.get(fd);
        if (raf == null) {
            return -1;
        }
        try {
            long base;
            if (whence == 0) base = 0L;
            else if (whence == 1) base = raf.getFilePointer();
            else if (whence == 2) base = raf.length();
            else return -1;
            long pos = base + offset;
            if (pos < 0) pos = 0;
            raf.seek(pos);
            fdEof.put(fd, Boolean.FALSE);
            return raf.getFilePointer();
        }
        catch (IOException e) {
            return -1;
        }
    }

    public long mir_sysio_tell(int fd) {
        RandomAccessFile raf = fdTable.get(fd);
        if (raf == null) {
            return -1;
        }
        try {
            return raf.getFilePointer();
        }
        catch (IOException e) {
            return -1;
        }
    }

    public int mir_sysio_feof(int fd) {
        return Boolean.TRUE.equals(fdEof.get(fd)) ? 1 : 0;
    }

    //
    // <stdarg.h> -- va_next() is the only half of cflat's va_list support
    // (see lib/stdarg.c) that needs a JVM-specific implementation:
    // va_init() itself is a CodeGenerator compile-time intrinsic (see
    // compileVaInit), since only the compiler knows where a given
    // function's "..." tail was marshalled to (see compileVarargTail) --
    // but once va_init() has handed back that address, walking forward
    // through it one 8-byte slot at a time is just ordinary pointer
    // arithmetic, identical in spirit to lib/stdarg.c's own x86
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
    // Public Java API: for plain Java code (not compiled cflat) sharing
    // this same JVM process with a compiled program, to inspect or
    // modify its simulated memory directly instead of reaching for
    // reflection. Every compiled program's own class already exposes a
    // public static "$runtime()" method returning its one shared
    // StandardRuntime instance (see CodeGenerator#emitRuntimeAccessor),
    // so "MyProgram.$runtime().readInt(addr)" (etc.) works directly, no
    // different from calling any other public method on any other
    // object -- and every method below reads/writes memory in exactly
    // the same layout (little-endian, via the same "buf" wrapping the
    // same "mem") a compiled program's own generated code already does,
    // so a value written by one side is always read correctly by the
    // other, with no separate byte-order bookkeeping for a caller to
    // get wrong. A cflat pointer is always just this "long" address, on
    // both sides of that boundary alike.
    //

    /** The compiled program's whole simulated address space, as a plain
     *  byte[] -- the very array every method in this class already
     *  reads and writes. Returned directly, not a copy: reading a large
     *  range (e.g. a decoded image's pixel bytes) this way costs
     *  nothing extra, at the cost of also being able to corrupt the
     *  compiled program's own state if written to carelessly -- prefer
     *  the typed read*()/write*() methods below for anything narrower
     *  than "I specifically need the backing array itself". */
    public byte[] memory() {
        return mem;
    }

    public byte readByte(long addr) {
        return mem[at(addr)];
    }

    public int readUnsignedByte(long addr) {
        return mem[at(addr)] & 0xFF;
    }

    public short readShort(long addr) {
        return buf.getShort(at(addr));
    }

    public int readUnsignedShort(long addr) {
        return buf.getShort(at(addr)) & 0xFFFF;
    }

    /** The byte order every compiled program's own generated code
     *  already reads/writes an "int" in (little-endian, matching "buf"
     *  above) -- readIntLE() is the exact same method under an explicit
     *  name, for a caller that would rather not depend on "readInt() is
     *  documented as little-endian" staying true forever. */
    public int readInt(long addr) {
        return buf.getInt(at(addr));
    }

    public int readIntLE(long addr) {
        return readInt(addr);
    }

    public long readUnsignedInt(long addr) {
        return ((long) readInt(addr)) & 0xFFFFFFFFL;
    }

    public long readLong(long addr) {
        return buf.getLong(at(addr));
    }

    public long readLongLE(long addr) {
        return readLong(addr);
    }

    /** A cflat pointer is itself just a "long" (see this class's own
     *  doc comment) -- readPointer()/writePointer() are readLong()/
     *  writeLong() under the name that actually describes what's being
     *  read or written, for code specifically walking pointers rather
     *  than reading an 8-byte integer. */
    public long readPointer(long addr) {
        return readLong(addr);
    }

    public float readFloat(long addr) {
        return buf.getFloat(at(addr));
    }

    public double readDouble(long addr) {
        return buf.getDouble(at(addr));
    }

    /** Copies "length" bytes starting at "addr" out into a fresh array
     *  -- e.g. a decoded image's own pixel bytes, or any other block a
     *  cflat pointer refers to. */
    public byte[] readBytes(long addr, int length) {
        byte[] result = new byte[length];
        System.arraycopy(mem, at(addr), result, 0, length);
        return result;
    }

    /** A NUL-terminated C string starting at "addr", decoded the same
     *  way this backend's own string literals are encoded in the first
     *  place (see CodeGenerator#encodeCString) -- UTF-8, so real
     *  non-ASCII text written by the compiled program (or by
     *  newString() below) round-trips correctly. Also used internally,
     *  above, for fopen()'s own path/mode decoding (ASCII either way
     *  for any real "mode" string, and UTF-8 is the more correct
     *  reading of a real filesystem path than this used to do). */
    public String readCString(long addr) {
        int start = at(addr);
        int end = start;
        while (mem[end] != 0) end++;
        return new String(mem, start, end - start, StandardCharsets.UTF_8);
    }

    public void writeByte(long addr, int value) {
        mem[at(addr)] = (byte) value;
    }

    public void writeShort(long addr, int value) {
        buf.putShort(at(addr), (short) value);
    }

    public void writeInt(long addr, int value) {
        buf.putInt(at(addr), value);
    }

    public void writeIntLE(long addr, int value) {
        writeInt(addr, value);
    }

    public void writeLong(long addr, long value) {
        buf.putLong(at(addr), value);
    }

    public void writeLongLE(long addr, long value) {
        writeLong(addr, value);
    }

    public void writePointer(long addr, long value) {
        writeLong(addr, value);
    }

    public void writeFloat(long addr, float value) {
        buf.putFloat(at(addr), value);
    }

    public void writeDouble(long addr, double value) {
        buf.putDouble(at(addr), value);
    }

    /** Copies "data" into memory starting at "addr" -- the caller is
     *  responsible for "addr" pointing at an already-allocated block
     *  (e.g. from malloc() below) at least "data.length" bytes long;
     *  nothing here checks. */
    public void writeBytes(long addr, byte[] data) {
        System.arraycopy(data, 0, mem, at(addr), data.length);
    }

    /** Allocates (via malloc() below) a fresh, NUL-terminated C string
     *  built from a Java String's own UTF-8 bytes, and returns its
     *  address -- the Java-facing equivalent of what a compiled
     *  program's own generated code already does internally to build
     *  one (see CodeGenerator's own "$newstr", used for e.g. argv),
     *  exposed here so external Java code building one itself (a
     *  filesystem path to hand to fopen(), say) doesn't need its own
     *  malloc()+writeBytes()+NUL boilerplate. Returns 0 (NULL), exactly
     *  like a failed malloc() itself, if allocation fails. */
    public long newString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long addr = malloc(bytes.length + 1);
        if (addr == 0) return 0;
        writeBytes(addr, bytes);
        writeByte(addr + bytes.length, 0);
        return addr;
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
