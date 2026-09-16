package net.loveruby.cflat.ast;
import net.loveruby.cflat.entity.DefinedVariable;

/** A local variable declaration that appears among a block's statements
 *  rather than only at the top (C99 "mixed declarations and code"). This
 *  is a pure placeholder marking *where* the declaration's initializer
 *  must run at runtime -- the DefinedVariable itself still lives in the
 *  enclosing BlockNode's variables() list, exactly as before, so scope
 *  resolution, type checking and dereference checking (which all walk
 *  that list directly) need no changes; only IRGenerator's visit(BlockNode)
 *  and visit(DefvarNode) care about this node's position in stmts(). */
public class DefvarNode extends StmtNode {
    protected DefinedVariable variable;

    public DefvarNode(DefinedVariable variable) {
        super(variable.location());
        this.variable = variable;
    }

    public DefinedVariable variable() {
        return variable;
    }

    protected void _dump(Dumper d) {
        d.printMember("variable", variable.name());
    }

    public <S,E> S accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
