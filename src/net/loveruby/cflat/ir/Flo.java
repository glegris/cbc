package net.loveruby.cflat.ir;
import net.loveruby.cflat.asm.*;

/** A floating-point constant (float or double, per type). Kept separate
 *  from Int (rather than shoehorning a double's bits into Int's long
 *  field) since the two need entirely different handling everywhere they
 *  reach codegen -- a JVM LDC of a Float/Double object, not an integer
 *  immediate. */
public class Flo extends Expr {
    protected double value;

    public Flo(Type type, double value) {
        super(type);
        this.value = value;
    }

    public double value() { return value; }

    public boolean isConstant() { return true; }

    public ImmediateValue asmValue() {
        throw new Error("must not happen: Flo#asmValue (the x86 backend "
                + "does not support floating-point types)");
    }

    public MemoryReference memref() {
        throw new Error("must not happen: Flo#memref");
    }

    public <S,E> E accept(IRVisitor<S,E> visitor) {
        return visitor.visit(this);
    }

    protected void _dump(Dumper d) {
        d.printMember("value", value);
    }
}
