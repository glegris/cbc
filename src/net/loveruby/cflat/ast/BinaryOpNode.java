package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;

public class BinaryOpNode extends ExprNode {
    protected String operator;
    protected ExprNode left, right;
    protected Type type;

    public BinaryOpNode(ExprNode left, String op, ExprNode right) {
        super();
        this.operator = op;
        this.left = left;
        this.right = right;
    }

    public BinaryOpNode(Type t, ExprNode left, String op, ExprNode right) {
        super();
        this.operator = op;
        this.left = left;
        this.right = right;
        this.type = t;
    }

    public String operator() {
        return operator;
    }

    public Type type() {
        return (type != null) ? type : left.type();
    }

    public void setType(Type type) {
        if (this.type != null)
            throw new Error("BinaryOp#setType called twice");
        this.type = type;
    }

    public ExprNode left() {
        return left;
    }

    public void setLeft(ExprNode left) {
        this.left = left;
    }

    public ExprNode right() {
        return right;
    }

    public void setRight(ExprNode right) {
        this.right = right;
    }

    public Location location() {
        return left.location();
    }

    /** Same deferred-validation pattern as AddressNode/AggregateLiteralNode
     *  (see either's own comment): a basic-arithmetic constant expression
     *  of two constant operands (e.g. a top-level "static float g =
     *  1.0f/2.2f;", as stb_image.h's own "stbi__h2l_gamma_i" is)
     *  is itself a compile-time constant, but DereferenceChecker's blunt
     *  top-level-initializer gate has no notion of looking inside this
     *  node at its own operands the way IRGenerator's foldStaticConstant
     *  does -- so this always reports true, deferring to that real,
     *  context-aware check (falling back to the same "not a compile-time
     *  constant" error an unfoldable aggregate leaf already gets). */
    public boolean isConstant() {
        return true;
    }

    protected void _dump(Dumper d) {
        d.printMember("operator", operator);
        d.printMember("left", left);
        d.printMember("right", right);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
