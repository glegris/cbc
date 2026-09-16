package net.loveruby.cflat.type;
import net.loveruby.cflat.ast.Location;

public class FloatTypeRef extends TypeRef {
    static public FloatTypeRef floatRef(Location loc) {
        return new FloatTypeRef("float", loc);
    }

    static public FloatTypeRef floatRef() {
        return new FloatTypeRef("float");
    }

    static public FloatTypeRef doubleRef(Location loc) {
        return new FloatTypeRef("double", loc);
    }

    static public FloatTypeRef doubleRef() {
        return new FloatTypeRef("double");
    }

    protected String name;

    public FloatTypeRef(String name) {
        this(name, null);
    }

    public FloatTypeRef(String name, Location loc) {
        super(loc);
        this.name = name;
    }

    public String name() {
        return name;
    }

    public boolean equals(Object other) {
        if (! (other instanceof FloatTypeRef)) return false;
        FloatTypeRef ref = (FloatTypeRef)other;
        return name.equals(ref.name);
    }

    public String toString() {
        return name;
    }
}
