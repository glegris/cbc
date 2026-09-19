package net.loveruby.cflat.type;

public class IntegerType extends Type {
    protected long size;
    protected boolean isSigned;
    protected String name;

    public IntegerType(long size, boolean isSigned, String name) {
        super();
        this.size = size;
        this.isSigned = isSigned;
        this.name = name;
    }

    public boolean isInteger() { return true; }
    public boolean isBool() { return name.equals("_Bool"); }
    public boolean isSigned() { return isSigned; }
    public boolean isScalar() { return true; }

    public long minValue() {
        return isSigned ? (long)-Math.pow(2, size * 8 - 1) : 0;
    }

    public long maxValue() {
        return isSigned ? (long)Math.pow(2, size * 8 - 1) - 1
                        : (long)Math.pow(2, size * 8) - 1;
    }

    public boolean isInDomain(long i) {
        return (minValue() <= i && i <= maxValue());
    }

    // Use default #equals
    //public boolean equals(Object other)

    public boolean isSameType(Type other) {
        if (! other.isInteger()) return false;
        return equals(other.getIntegerType());
    }

    public boolean isCompatible(Type other) {
        return (other.isInteger() && size <= other.size()) || other.isFloat();
    }

    public boolean isCastableTo(Type target) {
        // "(void)expr;" (C99 6.3.2.2: a void cast explicitly discards a
        // value) is common enough -- e.g. silencing an unused-value
        // warning, or a macro that only conditionally evaluates to
        // something meaningful (see import/assert.h) -- to accept
        // universally rather than reject as "no such cast".
        return (target.isInteger() || target.isPointer() || target.isFloat()
                || target.isVoid());
    }

    public long size() {
        return size;
    }

    public String toString() {
        return name;
    }
}
