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
            // relative sizes -- unlike a plain integer narrowing/
            // widening, where same-or-smaller size alone means the
            // underlying bits already mean the same thing and no real
            // instruction is needed.
            return ! type().isSameType(expr.type());
        }
        return type().size() > expr.type().size();
    }

    public Location location() {
        return typeNode.location();
    }

    protected void _dump(Dumper d) {
        d.printMember("typeNode", typeNode);
        d.printMember("expr", expr);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
