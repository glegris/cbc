package net.loveruby.cflat.type;

public class FloatType extends Type {
    protected long size;
    protected String name;

    public FloatType(long size, String name) {
        super();
        this.size = size;
        this.name = name;
    }

    public boolean isFloat() { return true; }
    public boolean isScalar() { return true; }
    // Every cflat floating type is signed; there is no "unsigned float".
    public boolean isSigned() { return true; }

    public boolean isSameType(Type other) {
        if (! other.isFloat()) return false;
        return size == other.size();
    }

    public boolean isCompatible(Type other) {
        return other.isInteger() || other.isFloat();
    }

    public boolean isCastableTo(Type target) {
        return target.isInteger() || target.isFloat();
    }

    public long size() {
        return size;
    }

    public String toString() {
        return name;
    }
}
