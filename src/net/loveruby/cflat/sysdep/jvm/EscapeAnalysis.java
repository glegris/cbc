package net.loveruby.cflat.sysdep.jvm;

import net.loveruby.cflat.ir.*;
import net.loveruby.cflat.entity.Entity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds every Entity (a local variable, parameter, or compiler-
 * synthesized temporary -- see IRGenerator#tmpVar) whose address
 * genuinely escapes its defining function: is captured as a first-class
 * VALUE somewhere (stored into another variable, passed as a call
 * argument, returned, used in arithmetic, ...), as opposed to an Addr
 * node that is merely this backend's IR's uniform "store a value here"
 * representation for EVERY plain "x = expr;" assignment. IRGenerator's
 * own assign() helper wraps every assignment's target in addressOf(),
 * so "x = 5;" always lowers to "Assign(Addr(x), 5)" regardless of
 * whether the source ever wrote "&x" anywhere -- an Addr node appearing
 * as exactly the direct lhs() of an Assign does NOT by itself mean the
 * entity's address is observed anywhere; only an Addr node appearing in
 * any OTHER position (an assignment's own rhs, a call argument, a
 * Bin/Uni operand, a Return value, ...) means its address was captured
 * as a value that could be read back and dereferenced later, possibly
 * from code that no longer statically knows which entity it started
 * from.
 *
 * A non-escaping entity's storage can safely become a genuine JVM local
 * variable slot (ILOAD/ISTORE, ...) instead of a frame-relative address
 * into this backend's own simulated heap -- see
 * CodeGenerator.FunctionCompiler's own "promotedSlot": every access to
 * it today goes through a GETSTATIC/computed-address/INVOKEVIRTUAL
 * sequence (a real, measured bottleneck -- see demos/minimp3's own
 * README), where a real JVM local is a single, nearly-free bytecode
 * instruction the JIT can freely register-allocate, and it also cannot
 * ever be observed to alias another such local (unlike two pointers
 * into the shared simulated heap), so no other analysis is needed to
 * promote it safely.
 *
 * Deliberately intra-procedural and syntactic: cflat's "&x" is always
 * lexically resolved to a single entity within the function that
 * declares it (there is no notion of taking the address of a variable
 * declared in another function), so a single linear pass over one
 * function's own IR is enough -- no fixpoint iteration, no whole-
 * program/interprocedural analysis.
 */
class EscapeAnalysis implements IRVisitor<Void, Void> {
    static Set<Entity> escapingEntities(List<Stmt> ir) {
        EscapeAnalysis a = new EscapeAnalysis();
        for (Stmt s : ir) {
            s.accept(a);
        }
        return a.escaping;
    }

    private final Set<Entity> escaping = new HashSet<Entity>();

    private EscapeAnalysis() { }

    /** Visits "e" in a context where, if it turns out to be a plain Addr
     *  node, that Addr is a genuine escaping USE (its entity's address
     *  is observed as a value) -- every position except the direct lhs()
     *  of an Assign should call this rather than e.accept(this) so an
     *  Addr reached there is recorded, not silently walked past (Addr
     *  itself has no child expression to recurse into). */
    private void walkAsValue(Expr e) {
        if (e == null) return;
        if (e instanceof Addr) {
            escaping.add(((Addr) e).entity());
            return;
        }
        e.accept(this);
    }

    public Void visit(ExprStmt s) {
        walkAsValue(s.expr());
        return null;
    }

    public Void visit(Assign s) {
        // The lhs is a plain "store a value here" target -- not itself
        // an escaping use -- UNLESS it's something other than a bare
        // Addr node (e.g. "arr[f(&y)] = v;" still has "&y" escape here,
        // even though the outer assignment's own target isn't a simple
        // named variable), in which case it's walked like any other
        // value-producing expression so a nested Addr inside it is still
        // found.
        if (!(s.lhs() instanceof Addr)) {
            walkAsValue(s.lhs());
        }
        walkAsValue(s.rhs());
        return null;
    }

    public Void visit(CJump s) {
        walkAsValue(s.cond());
        return null;
    }

    public Void visit(Jump s) {
        return null;
    }

    public Void visit(Switch s) {
        walkAsValue(s.cond());
        return null;
    }

    public Void visit(LabelStmt s) {
        return null;
    }

    public Void visit(Return s) {
        walkAsValue(s.expr());
        return null;
    }

    public Void visit(Uni s) {
        walkAsValue(s.expr());
        return null;
    }

    public Void visit(Bin s) {
        walkAsValue(s.left());
        walkAsValue(s.right());
        return null;
    }

    public Void visit(Call s) {
        walkAsValue(s.expr());
        for (Expr arg : s.args()) {
            walkAsValue(arg);
        }
        return null;
    }

    public Void visit(Addr s) {
        // Only reached if some Expr type visits an Addr child directly
        // via accept() rather than walkAsValue() -- treat it the same
        // way defensively; currently nothing does, since every Addr-
        // producing site is itself a leaf reached through walkAsValue().
        escaping.add(s.entity());
        return null;
    }

    public Void visit(Mem s) {
        walkAsValue(s.expr());
        return null;
    }

    public Void visit(Var s) {
        return null;
    }

    public Void visit(Int s) {
        return null;
    }

    public Void visit(Flo s) {
        return null;
    }

    public Void visit(Str s) {
        return null;
    }
}
