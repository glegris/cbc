package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;

public class UnaryOpNode extends ExprNode {
    protected String operator;
    protected ExprNode expr;
    protected Type opType;
    // Overrides type() when set -- used only for "!", whose own result
    // is always "int" (C99 6.5.3.3p5) regardless of its operand's type
    // (unlike +/-/~, "!" doesn't promote or otherwise touch its
    // operand's own type at all, so this can't reuse the
    // promote-the-operand-via-a-CastNode trick TypeChecker's own
    // promoteUnaryOperand() uses for those three).
    protected Type type;

    public UnaryOpNode(String op, ExprNode expr) {
        this.operator = op;
        this.expr = expr;
    }

    public String operator() {
        return operator;
    }

    public Type type() {
        return (type != null) ? type : expr.type();
    }

    public void setType(Type t) {
        this.type = t;
    }

    public void setOpType(Type t) {
        this.opType = t;
    }

    public Type opType() {
        return opType;
    }

    public ExprNode expr() {
        return expr;
    }

    public void setExpr(ExprNode expr) {
        this.expr = expr;
    }

    public Location location() {
        return expr.location();
    }

    protected void _dump(Dumper d) {
        d.printMember("operator", operator);
        d.printMember("expr", expr);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
