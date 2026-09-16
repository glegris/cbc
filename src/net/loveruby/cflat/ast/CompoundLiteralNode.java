package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;

/** A C99 compound literal: "(type){ initializer-list }", e.g.
 *  "(struct point){1, 2}" or "(int[3]){1, 2, 3}". Unlike a cast, this is
 *  an expression that creates a fresh object with automatic storage
 *  duration (a modifiable lvalue, like any other local variable) and
 *  initializes it exactly like "type tmp = { initializer-list };" would
 *  -- see IRGenerator#visit(CompoundLiteralNode), which lowers it to
 *  precisely that: a compiler-synthesized temporary variable (the same
 *  mechanism already used for e.g. an assignment-as-expression's result)
 *  initialized via the existing aggregate-literal lowering, evaluating
 *  to a reference to that temporary.
 *
 *  Extends LHSNode (like VariableNode, MemberNode, ...) rather than
 *  plain ExprNode so that "&(type){...}" and array-vs-struct/scalar
 *  addressing (DereferenceChecker#visit(AddressNode) via isLoadable())
 *  work exactly like they already do for a named variable of the same
 *  type, with no special-casing needed there. */
public class CompoundLiteralNode extends LHSNode {
    protected TypeNode typeNode;
    protected AggregateLiteralNode literal;

    public CompoundLiteralNode(TypeNode typeNode, AggregateLiteralNode literal) {
        this.typeNode = typeNode;
        this.literal = literal;
    }

    protected Type origType() {
        return typeNode.type();
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    public AggregateLiteralNode literal() {
        return literal;
    }

    public Location location() {
        return typeNode.location();
    }

    protected void _dump(Dumper d) {
        if (type != null) {
            d.printMember("type", type);
        }
        d.printMember("typeNode", typeNode);
        d.printMember("literal", literal);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
