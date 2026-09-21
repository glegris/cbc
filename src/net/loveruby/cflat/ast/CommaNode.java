package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;

/** C99 6.5.17's comma operator: "left, right" evaluates left (for its
 *  side effect only -- its own value and type are discarded entirely),
 *  then evaluates and yields right, whose type is this whole node's own
 *  type. Only ever produced by the parser at a handful of call sites
 *  (see Parser.jj's commaExpr()) -- a bare "," means something else
 *  (the next argument/element) everywhere else a comma can appear. */
public class CommaNode extends ExprNode {
    protected ExprNode left, right;

    public CommaNode(ExprNode left, ExprNode right) {
        super();
        this.left = left;
        this.right = right;
    }

    public ExprNode left() {
        return left;
    }

    public ExprNode right() {
        return right;
    }

    public Type type() {
        return right.type();
    }

    public Location location() {
        return left.location();
    }

    protected void _dump(Dumper d) {
        d.printMember("left", left);
        d.printMember("right", right);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
