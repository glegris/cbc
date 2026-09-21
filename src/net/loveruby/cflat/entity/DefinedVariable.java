package net.loveruby.cflat.entity;
import net.loveruby.cflat.type.Type;
import net.loveruby.cflat.ast.TypeNode;
import net.loveruby.cflat.ast.ExprNode;
import net.loveruby.cflat.ir.Expr;
import net.loveruby.cflat.ir.StaticInitEntry;
import net.loveruby.cflat.asm.Symbol;
import net.loveruby.cflat.asm.NamedSymbol;
import java.util.List;

public class DefinedVariable extends Variable {
    protected ExprNode initializer;
    protected Expr ir;
    protected List<StaticInitEntry> staticInitEntries;
    protected long sequence;
    protected Symbol symbol;

    public DefinedVariable(boolean priv, TypeNode type,
                           String name, ExprNode init) {
        super(priv, type, name);
        initializer = init;
        sequence = -1;
    }

    static private long tmpSeq = 0;

    static public DefinedVariable tmp(Type t) {
        return new DefinedVariable(false,
                new TypeNode(t), "@tmp" + tmpSeq++, null);
    }

    public boolean isDefined() {
        return true;
    }

    public void setSequence(long seq) {
        this.sequence = seq;
    }

    public String symbolString() {
        return (sequence < 0) ? name : (name + "." + sequence);
    }

    /** Rebinds this variable's own type -- used only by TypeChecker to
     *  resolve "T x[] = {...};"'s incomplete array type into a real,
     *  sized one once the initializer's own element count is known
     *  (TypeResolver, which runs first, has no such count to work with
     *  yet). The replacement TypeNode must already carry a *resolved*
     *  Type (built via TypeNode's Type constructor, not its TypeRef
     *  one) since nothing will resolve it again. */
    public void setTypeNode(TypeNode t) {
        this.typeNode = t;
    }

    public boolean hasInitializer() {
        return (initializer != null);
    }

    public boolean isInitialized() {
        return hasInitializer();
    }

    public ExprNode initializer() {
        return initializer;
    }

    public void setInitializer(ExprNode expr) {
        this.initializer = expr;
    }

    public void setIR(Expr expr) {
        this.ir = expr;
    }

    public Expr ir() { return ir; }

    /** Set instead of ir() for a "{...}" (aggregate) initializer at
     *  static storage duration (a global, or a "static" local): a flat
     *  list of (byte offset, compile-time-constant value) leaves, since
     *  there is no single Expr that could represent "this struct/array's
     *  several members/elements" the way a plain scalar initializer can.
     *  See sysdep/{x86,jvm}/CodeGenerator's static-data emission for how
     *  each backend turns this into actual bytes. */
    public void setStaticInitEntries(List<StaticInitEntry> entries) {
        this.staticInitEntries = entries;
    }

    public boolean hasStaticInitEntries() {
        return staticInitEntries != null;
    }

    public List<StaticInitEntry> staticInitEntries() {
        return staticInitEntries;
    }

    protected void _dump(net.loveruby.cflat.ast.Dumper d) {
        d.printMember("name", name);
        d.printMember("isPrivate", isPrivate);
        d.printMember("typeNode", typeNode);
        d.printMember("initializer", initializer);
    }

    public <T> T accept(EntityVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
