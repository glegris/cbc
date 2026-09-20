package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;

public class CastNode extends ExprNode {
    protected TypeNode typeNode;
    protected ExprNode expr;

    public CastNode(Type t, ExprNode expr) {
        this(new TypeNode(t), expr);
    }

    public CastNode(TypeNode t, ExprNode expr) {
        this.typeNode = t;
        this.expr = expr;
    }

    public Type type() {
        return typeNode.type();
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    public ExprNode expr() {
        return expr;
    }

    public boolean isLvalue() { return expr.isLvalue(); }
    public boolean isAssignable() { return expr.isAssignable(); }

    public boolean isEffectiveCast() {
        if (type().isFloat() || expr.type().isFloat()) {
            // A conversion to/from a floating type is never a no-op
            // bit-reinterpretation, regardless of the two types'
            // relative sizes.
            return ! type().isSameType(expr.type());
        }
        // A cast between two integer types of the SAME size is a true
        // no-op (signed/unsigned reinterpretation of an identical bit
        // pattern needs no instruction on either backend). A WIDENING
        // cast obviously needs one (to sign/zero-extend the extra
        // bits). A NARROWING cast needs one too, even though it might
        // look "free" at a glance (the low bits are already right,
        // same as widening's -- just drop the rest): a narrower value
        // is stored back into a full-width register/slot with no
        // separate representation of its own on either backend, so
        // whatever garbage happened to be sitting in the bits above
        // the narrower width (e.g. the result of some wider arithmetic
        // this cast is truncating) stays there unless an actual
        // instruction masks/re-extends it -- and that garbage is very
        // much observable by anything that later reads the value at
        // its ostensibly-still-wide representation (a truthiness test,
        // a comparison, further arithmetic, ...), not just by a value
        // that immediately gets stored into an equally-narrow variable
        // (where the store's own width happens to mask it anyway).
        return type().size() != expr.type().size();
    }

    /** A compiler-synthesized cast (TypeChecker's implicitCast, via the
     *  Type-only constructor above) has a bare TypeNode with no TypeRef
     *  of its own, so typeNode.location() is null -- fall back to the
     *  wrapped expression's location, so an error reported against a
     *  synthesized cast (e.g. "not a compile-time constant" from
     *  IRGenerator's static-initializer folding) still points somewhere
     *  in the source instead of crashing on a null location. */
    public Location location() {
        Location loc = typeNode.location();
        return (loc != null) ? loc : expr.location();
    }

    protected void _dump(Dumper d) {
        d.printMember("typeNode", typeNode);
        d.printMember("expr", expr);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
