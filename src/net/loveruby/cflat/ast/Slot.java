package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;

public class Slot extends Node {
    static final protected long notBitField = -1;

    protected TypeNode typeNode;
    protected String name;
    protected long offset;
    protected long bitWidth;
    protected long bitOffset;

    public Slot(TypeNode t, String n) {
        this(t, n, notBitField);
    }

    /** A bit-field member ("unsigned x : 3;"): "width" is its width in
     *  bits. "type" is still the field's own *declared* type (e.g.
     *  "unsigned int") -- the storage unit several sibling bit-fields
     *  end up sharing once StructType#computeOffsets() packs them
     *  together, not the field's own narrower effective range -- with
     *  "bitOffset" (set by computeOffsets(), like "offset" already is)
     *  giving this field's own position within that shared unit. */
    public Slot(TypeNode t, String n, long width) {
        typeNode = t;
        name = n;
        offset = Type.sizeUnknown;
        bitWidth = width;
        bitOffset = 0;
    }

    public boolean isBitField() {
        return bitWidth != notBitField;
    }

    public long bitWidth() {
        return bitWidth;
    }

    public long bitOffset() {
        return bitOffset;
    }

    public void setBitOffset(long bitOffset) {
        this.bitOffset = bitOffset;
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    public TypeRef typeRef() {
        return typeNode.typeRef();
    }

    public Type type() {
        return typeNode.type();
    }

    public String name() {
        return name;
    }

    public long size() {
        return type().size();
    }

    public long allocSize() {
        return type().allocSize();
    }

    public long alignment() {
        return type().alignment();
    }

    public long offset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public Location location() {
        return typeNode.location();
    }

    protected void _dump(Dumper d) {
        d.printMember("name", name);
        d.printMember("typeNode", typeNode);
    }
}
