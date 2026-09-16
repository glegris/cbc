package net.loveruby.cflat.type;

public class QualifiedTypeRef extends TypeRef {
    protected TypeRef baseType;
    protected boolean isConst;
    protected boolean isVolatile;

    public QualifiedTypeRef(TypeRef baseType, boolean isConst, boolean isVolatile) {
        super(baseType.location());
        this.baseType = baseType;
        this.isConst = isConst;
        this.isVolatile = isVolatile;
    }

    public TypeRef baseType() {
        return baseType;
    }

    public boolean isConst() {
        return isConst;
    }

    public boolean isVolatile() {
        return isVolatile;
    }

    public boolean equals(Object other) {
        if (! (other instanceof QualifiedTypeRef)) return false;
        QualifiedTypeRef ref = (QualifiedTypeRef)other;
        return baseType.equals(ref.baseType)
                && isConst == ref.isConst && isVolatile == ref.isVolatile;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isConst) sb.append("const ");
        if (isVolatile) sb.append("volatile ");
        sb.append(baseType.toString());
        return sb.toString();
    }
}
