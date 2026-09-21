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
        // A run of bit-fields shares one "storage unit" (sized by
        // whichever of them opened it) until one doesn't fit, a
        // differently-sized bit-field type shows up, or a plain member
        // ends the run -- see the bit-field branch below.
        long unitBitsUsed = 0;
        long unitOffset = 0;
        long unitSizeBits = 0;
        int lastIndex = members().size() - 1;
        for (int i = 0; i < members().size(); i++) {
            Slot s = members().get(i);
            if (s.isBitField()) {
                long width = s.bitWidth();
                long typeBits = s.type().allocSize() * 8;
                if (width == 0) {
                    // A zero-width bit-field (always unnamed in real C,
                    // though this compiler doesn't insist) reserves no
                    // storage of its own -- C99 6.7.2.1p12's own point
                    // of it is exactly to force whatever bit-field
                    // comes *after* it into a fresh storage unit.
                    unitBitsUsed = 0;
                    s.setOffset(offset);
                    s.setBitOffset(0);
                    continue;
                }
                boolean needsNewUnit = (unitBitsUsed == 0)
                        || (unitSizeBits != typeBits)
                        || (unitBitsUsed + width > unitSizeBits);
                if (needsNewUnit) {
                    offset = AsmUtils.align(offset, s.alignment());
                    unitOffset = offset;
                    unitSizeBits = typeBits;
                    unitBitsUsed = 0;
                    offset += typeBits / 8;
                }
                s.setOffset(unitOffset);
                s.setBitOffset(unitBitsUsed);
                unitBitsUsed += width;
                maxAlign = Math.max(maxAlign, s.alignment());
            }
            else {
                // A plain member never shares a storage unit with a
                // bit-field before it -- that unit's bytes are already
                // reserved in "offset" from when it was opened above,
                // so just close it out and lay this member out as
                // usual.
                unitBitsUsed = 0;
                // C99 6.7.2.1p18's "flexible array member": an
                // incomplete array ("int data[];", no size at all) as a
                // struct's very last member contributes *nothing* to
                // the struct's own size -- it's a placeholder for
                // however many elements a caller allocates room for
                // past the struct itself (e.g. "malloc(sizeof(struct
                // S) + n * sizeof(int))"), not a real, sized member --
                // though the struct must still be laid out so this
                // member itself starts at an address properly aligned
                // for its own element type. Without this,
                // allocSize() fell back to ArrayType's own decayed-to-
                // pointer size (meant for an array *parameter*, not a
                // struct member) for any incomplete array, silently
                // inflating the struct's sizeof by a whole pointer's
                // worth of bytes it never actually reserves.
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
        }
        cachedSize = AsmUtils.align(offset, maxAlign);
        cachedAlign = maxAlign;
    }

    public String toString() {
        return "struct " + name;
    }
}
