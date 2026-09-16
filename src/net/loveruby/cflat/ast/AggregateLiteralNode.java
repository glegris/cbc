package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;
import java.util.List;

/** A brace-enclosed initializer list, "{ e0, e1, ... }", used only as a
 *  variable's declared initializer (never as a general sub-expression --
 *  there is no standalone value a bare "{1,2,3}" could have without a
 *  target type to check it against, so this node is only ever produced
 *  as DefinedVariable#initializer() and is unwrapped by TypeChecker/
 *  IRGenerator before it could reach a generic expression-visiting
 *  context). An element may itself be another AggregateLiteralNode, for
 *  a nested array/struct member. */
public class AggregateLiteralNode extends ExprNode {
    protected Location location;
    protected List<ExprNode> elements;
    protected Type type;

    public AggregateLiteralNode(Location loc, List<ExprNode> elements) {
        super();
        this.location = loc;
        this.elements = elements;
    }

    public List<ExprNode> elements() {
        return elements;
    }

    /** DereferenceChecker requires a top-level (global) variable's
     *  initializer to be "constant" before IRGenerator ever runs, using
     *  this flag alone -- it has no notion of looking inside an
     *  aggregate literal at its individual elements. Rather than
     *  duplicate that check here, this always reports true and leaves
     *  the real, per-leaf validation to IRGenerator#foldStaticConstant,
     *  which can point at the exact offending element instead of the
     *  whole initializer. A local (non-static) variable's initializer
     *  is never held to this "constant" requirement in the first place
     *  (only checkToplevelVariable calls checkConstant), so this only
     *  affects globals/statics either way. */
    public boolean isConstant() {
        return true;
    }

    /** Set once TypeChecker knows what this literal is initializing. */
    public void setType(Type t) {
        this.type = t;
    }

    public Type type() {
        if (type == null) {
            throw new Error("AggregateLiteralNode#type called before it was resolved");
        }
        return type;
    }

    public Location location() {
        return location;
    }

    protected void _dump(Dumper d) {
        d.printNodeList("elements", elements);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
