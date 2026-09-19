package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;

/** A C99 compound literal: "(type){ initializer-list }", e.g.
 *  "(struct point){1, 2}" or "(int[3]){1, 2, 3}". Unlike a cast, this is
 *  an expression that creates a fresh object (a modifiable lvalue, like
 *  any other variable) and initializes it exactly like
 *  "type tmp = { initializer-list };" would. Its storage duration
 *  follows the same C99 rule as any other variable: static at file
 *  scope or behind a "static" local, automatic otherwise -- but only
 *  when it appears as a *whole* initializer, directly or as an already-
 *  braced nested element (TypeChecker's checkVariable/checkAggregateElement
 *  unwrap it into a plain brace initializer whenever its type matches
 *  the target exactly, before storage duration is even decided, so
 *  IRGenerator never has to special-case it there). Nested inside some
 *  other expression (e.g. "&(type){...}", or any other spot that isn't
 *  itself a variable's or an element's whole initializer) it still only
 *  has automatic storage duration -- see
 *  IRGenerator#visit(CompoundLiteralNode), which lowers exactly that
 *  remaining general case to a compiler-synthesized temporary variable
 *  (the same mechanism already used for e.g. an assignment-as-expression's
 *  result) initialized via the existing aggregate-literal lowering,
 *  evaluating to a reference to that temporary.
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

    /** Same reasoning as AggregateLiteralNode#isConstant(): DereferenceChecker
     *  requires a top-level variable's initializer to pass this check before
     *  TypeChecker ever runs, which is too early to know whether this
     *  compound literal will end up needing static storage duration (a
     *  file-scope variable, or a "static" local) or automatic (a plain
     *  local) -- so this always reports true, and TypeChecker/IRGenerator
     *  do the real, context-aware validation afterward: TypeChecker's
     *  checkVariable unwraps "T x = (T){...};" into a plain brace
     *  initializer whenever the types match exactly (the only shape that
     *  can appear as a whole top-level initializer, static or automatic
     *  alike), and IRGenerator folds a nested compound-literal leaf inside
     *  a static aggregate the same way (see flattenStaticElement) -- only
     *  the fully general case (a compound literal nested in an arbitrary
     *  expression, e.g. under "&") is still limited to automatic storage
     *  (see visit(CompoundLiteralNode)'s own scope note). */
    public boolean isConstant() {
        return true;
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
