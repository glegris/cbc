package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.Type;
import net.loveruby.cflat.entity.Entity;
import net.loveruby.cflat.entity.DefinedVariable;

public class VariableNode extends LHSNode {
    private Location location;
    private String name;
    private Entity entity;

    public VariableNode(Location loc, String name) {
        this.location = loc;
        this.name = name;
    }

    public VariableNode(DefinedVariable var) {
        this.entity = var;
        this.name = var.name();
    }

    public String name() {
        return name;
    }

    public boolean isResolved() {
        return (entity != null);
    }

    public Entity entity() {
        if (entity == null) {
            throw new Error("VariableNode.entity == null");
        }
        return entity;
    }

    public void setEntity(Entity ent) {
        entity = ent;
    }

    /*==============================================
    =            fix constant entity bug            =
    ==============================================*/
    public boolean isLvalue() { 
        if (entity.isConstant()) {
            return false;
        }
        return true; 
    }

    public boolean isAssignable() {
        if (entity.isConstant()) {
            return false;
        }
        return isLoadable();
    }
    /*=====  End of fix constant entity bug  ======*/

    /** Same deferred-validation pattern as AddressNode/AggregateLiteralNode
     *  (see either's own comment): DereferenceChecker's blunt top-level-
     *  initializer gate runs too early to tell a genuinely non-constant
     *  reference (an ordinary global) from a bare function name, which
     *  decays to its own link-time-constant address exactly like
     *  visit(VariableNode) already resolves it at runtime (see its own
     *  isLoadable() check) -- e.g. stb_image.h's own "static
     *  stbi_io_callbacks stbi__stdio_callbacks = { stbi__stdio_read,
     *  ... };". So this always reports true, and IRGenerator's own
     *  foldStaticConstant does the real, context-aware check, correctly
     *  rejecting an ordinary non-constant global reference with the same
     *  "not a compile-time constant" error an unfoldable aggregate leaf
     *  already gets. */
    public boolean isConstant() {
        return true;
    }
    
    public TypeNode typeNode() {
        return entity().typeNode();
    }

    public boolean isParameter() {
        return entity().isParameter();
    }

    protected Type origType() {
        return entity().type();
    }

    public Location location() {
        return location;
    }

    protected void _dump(Dumper d) {
        if (type != null) {
            d.printMember("type", type);
        }
        d.printMember("name", name, isResolved());
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
