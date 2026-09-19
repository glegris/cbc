package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;

public class AddressNode extends ExprNode {
    final ExprNode expr;
    Type type;

    public AddressNode(ExprNode expr) {
        this.expr = expr;
    }

    public ExprNode expr() {
        return expr;
    }

    public Type type() {
        if (type == null) throw new Error("type is null");
        return type;
    }

    /** Decides type of this node.
     * This method is called from DereferenceChecker. */
    public void setType(Type type) {
        if (this.type != null) throw new Error("type set twice");
        this.type = type;
    }

    public Location location() {
        return expr.location();
    }

    /** Same deferred-validation pattern as AggregateLiteralNode/
     *  CompoundLiteralNode: DereferenceChecker's blunt "is this whole
     *  top-level initializer a constant?" gate runs before IRGenerator
     *  ever sees this node, too early to tell whether "&expr" points at
     *  something IRGenerator's foldStaticConstant can actually turn into
     *  a link-time constant address (a global variable, or a compound
     *  literal -- both get their own static-storage object) or something
     *  it can't (e.g. "&globalArray[i]" with a non-constant "i"). So this
     *  always reports true -- DereferenceChecker's own isLvalue() check
     *  (visit(AddressNode)) still rejects "&" of a non-lvalue regardless
     *  -- and foldStaticConstant does the real, context-aware check,
     *  falling back to the same "not a compile-time constant" error an
     *  unfoldable aggregate leaf already gets. */
    public boolean isConstant() {
        return true;
    }

    protected void _dump(Dumper d) {
        if (type != null) {
            d.printMember("type", type);
        }
        d.printMember("expr", expr);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
