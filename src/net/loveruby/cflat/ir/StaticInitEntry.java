package net.loveruby.cflat.ir;

/** One leaf of a global/static-local aggregate ("{...}") initializer:
 *  a compile-time-constant value to place at a given byte offset within
 *  the variable's own allocated storage. Any byte not covered by one of
 *  these is left zero (matching both backends' default-zeroed static
 *  storage), so a partial initializer list doesn't need its own
 *  trailing zero-fill entries. */
public class StaticInitEntry {
    private final long offset;
    private final Expr value;

    public StaticInitEntry(long offset, Expr value) {
        this.offset = offset;
        this.value = value;
    }

    public long offset() { return offset; }
    public Expr value() { return value; }
}
