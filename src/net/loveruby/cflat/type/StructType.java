package net.loveruby.cflat.type;
import net.loveruby.cflat.ast.Slot;
import net.loveruby.cflat.ast.Location;
import net.loveruby.cflat.utils.AsmUtils;
import java.util.*;

public class StructType extends CompositeType {
    public StructType(String name, List<Slot> membs, Location loc) {
        super(name, membs, loc);
    }

    public boolean isStruct() { return true; }

    public boolean isSameType(Type other) {
        if (! other.isStruct()) return false;
        return equals(other.getStructType());
    }

    protected void computeOffsets() {
        long offset = 0;
        long maxAlign = 1;
        int lastIndex = members().size() - 1;
        for (int i = 0; i < members().size(); i++) {
            Slot s = members().get(i);
            // C99 6.7.2.1p18's "flexible array member": an incomplete
            // array ("int data[];", no size at all) as a struct's very
            // last member contributes *nothing* to the struct's own
            // size -- it's a placeholder for however many elements a
            // caller allocates room for past the struct itself (e.g.
            // "malloc(sizeof(struct S) + n * sizeof(int))"), not a
            // real, sized member -- though the struct must still be
            // laid out so this member itself starts at an address
            // properly aligned for its own element type. Without this,
            // allocSize() fell back to ArrayType's own decayed-to-
            // pointer size (meant for an array *parameter*, not a
            // struct member) for any incomplete array, silently
            // inflating the struct's sizeof by a whole pointer's worth
            // of bytes it never actually reserves.
            boolean isFlexibleArrayMember = (i == lastIndex)
                    && s.type().isArray()
                    && ! s.type().getArrayType().isAllocatedArray();
            if (isFlexibleArrayMember) {
                offset = AsmUtils.align(offset, s.alignment());
                s.setOffset(offset);
            }
            else {
                offset = AsmUtils.align(offset, s.allocSize());
                s.setOffset(offset);
                offset += s.allocSize();
            }
            maxAlign = Math.max(maxAlign, s.alignment());
        }
        cachedSize = AsmUtils.align(offset, maxAlign);
        cachedAlign = maxAlign;
    }

    public String toString() {
        return "struct " + name;
    }
}
