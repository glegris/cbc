package net.loveruby.cflat.ast;
import net.loveruby.cflat.type.*;
import java.util.ArrayList;
import java.util.List;

/** A brace-enclosed initializer list, "{ e0, e1, ... }", used only as a
 *  variable's declared initializer (never as a general sub-expression --
 *  there is no standalone value a bare "{1,2,3}" could have without a
 *  target type to check it against, so this node is only ever produced
 *  as DefinedVariable#initializer() and is unwrapped by TypeChecker/
 *  IRGenerator before it could reach a generic expression-visiting
 *  context). An element may itself be another AggregateLiteralNode, for
 *  a nested array/struct member.
 *
 *  Each element may carry a C99 designator (".member" or "[index]",
 *  recorded in members()/indices() -- null when absent), which sets
 *  where that element lands instead of the usual "next position after
 *  the previous element" -- see resolvePositions(). Only one designator
 *  per element, never chained/nested ("[i].member", ".a.b") and never
 *  both at once. */
public class AggregateLiteralNode extends ExprNode {
    protected Location location;
    protected List<ExprNode> elements;
    protected List<String> members;
    protected List<Long> indices;
    protected Type type;

    public AggregateLiteralNode(Location loc, List<ExprNode> elements,
            List<String> members, List<Long> indices) {
        super();
        this.location = loc;
        this.elements = elements;
        this.members = members;
        this.indices = indices;
    }

    public List<ExprNode> elements() {
        return elements;
    }

    /** The ".member" designator name for each element, or null where
     *  that element has no member designator (never non-null at the
     *  same index as indices()). */
    public List<String> members() {
        return members;
    }

    /** The "[index]" designator value for each element, or null where
     *  that element has no index designator. */
    public List<Long> indices() {
        return indices;
    }

    /** Resolves each element's target array-index / struct-member-index
     *  (C99 6.7.9p17,20): a designator sets the position outright,
     *  otherwise it continues right after the previous element's
     *  position (starting at 0). memberIndexOf, when this literal is
     *  initializing a struct/union, maps a ".member" designator's name
     *  to that member's declaration-order index, or returns -1 for an
     *  unknown member (the caller -- TypeChecker -- reports that as an
     *  error; this method just passes -1 through so the resolved
     *  position list always has one entry per element). Pass null for
     *  memberIndexOf when initializing an array (only "[index]" makes
     *  sense there; a stray ".member" is likewise left to the caller to
     *  reject however it sees fit -- resolvePositions treats it as -1
     *  too, via the null-safe check below). */
    public List<Integer> resolvePositions(MemberIndexOf memberIndexOf) {
        List<Integer> result = new ArrayList<Integer>(elements.size());
        int cursor = 0;
        for (int i = 0; i < elements.size(); i++) {
            Long idx = indices.get(i);
            String member = members.get(i);
            if (idx != null) {
                cursor = idx.intValue();
            }
            else if (member != null) {
                cursor = (memberIndexOf == null) ? -1 : memberIndexOf.indexOf(member);
            }
            result.add(cursor);
            cursor++;
        }
        return result;
    }

    /** Callback interface for resolvePositions, rather than a direct
     *  dependency on CompositeType/Slot here (kept out of the ast
     *  package's own vocabulary). */
    public interface MemberIndexOf {
        int indexOf(String name);
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
