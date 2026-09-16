package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;

public class FloatLiteralNode extends LiteralNode {
    protected double value;

    public FloatLiteralNode(Location loc, TypeRef ref, double value) {
        super(loc, ref);
        this.value = value;
    }

    public double value() {
        return value;
    }

    protected void _dump(Dumper d) {
        d.printMember("typeNode", typeNode);
        d.printMember("value", value);
    }

    public <S,E> E accept(ASTVisitor<S,E> visitor) {
        return visitor.visit(this);
    }
}
