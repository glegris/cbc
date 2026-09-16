package net.loveruby.cflat.type;

/** A const- and/or volatile-qualified type, e.g. "const int" or
 *  "const char*". This wraps and forwards to the real underlying type
 *  for everything except isConst()/isVolatile() themselves (mirroring
 *  how UserType forwards to a typedef's real type), so the rest of the
 *  compiler -- which always asks a Type "isInteger()"/"isPointer()"/...
 *  rather than testing its concrete class -- treats a qualified type
 *  exactly like the type it qualifies, except that TypeChecker rejects
 *  assigning (or ++/--) through a const-qualified lvalue. "volatile" is
 *  otherwise a no-op here: neither backend reorders or caches memory
 *  accesses in a way volatile would need to suppress. */
public class QualifiedType extends Type {
    protected Type real;
    protected boolean isConst;
    protected boolean isVolatile;

    public QualifiedType(Type real, boolean isConst, boolean isVolatile) {
        this.real = real;
        this.isConst = isConst;
        this.isVolatile = isVolatile;
    }

    public Type realType() {
        return real;
    }

    public boolean isConst() { return isConst; }
    public boolean isVolatile() { return isVolatile; }

    public long size() { return real.size(); }
    public long allocSize() { return real.allocSize(); }
    public long alignment() { return real.alignment(); }

    public boolean isVoid() { return real.isVoid(); }
    public boolean isInt() { return real.isInt(); }
    public boolean isInteger() { return real.isInteger(); }
    public boolean isFloat() { return real.isFloat(); }
    public boolean isSigned() { return real.isSigned(); }
    public boolean isPointer() { return real.isPointer(); }
    public boolean isArray() { return real.isArray(); }
    public boolean isCompositeType() { return real.isCompositeType(); }
    public boolean isStruct() { return real.isStruct(); }
    public boolean isUnion() { return real.isUnion(); }
    public boolean isUserType() { return real.isUserType(); }
    public boolean isFunction() { return real.isFunction(); }

    public boolean isAllocatedArray() { return real.isAllocatedArray(); }
    public boolean isIncompleteArray() { return real.isIncompleteArray(); }
    public boolean isScalar() { return real.isScalar(); }
    public boolean isCallable() { return real.isCallable(); }

    public Type baseType() { return real.baseType(); }

    public boolean isSameType(Type other) { return real.isSameType(other); }
    public boolean isCompatible(Type other) { return real.isCompatible(other); }
    public boolean isCastableTo(Type target) { return real.isCastableTo(target); }

    public IntegerType getIntegerType() { return real.getIntegerType(); }
    public FloatType getFloatType() { return real.getFloatType(); }
    public PointerType getPointerType() { return real.getPointerType(); }
    public CompositeType getCompositeType() { return real.getCompositeType(); }
    public StructType getStructType() { return real.getStructType(); }
    public UnionType getUnionType() { return real.getUnionType(); }
    public ArrayType getArrayType() { return real.getArrayType(); }
    public FunctionType getFunctionType() { return real.getFunctionType(); }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isConst) sb.append("const ");
        if (isVolatile) sb.append("volatile ");
        sb.append(real.toString());
        return sb.toString();
    }
}
