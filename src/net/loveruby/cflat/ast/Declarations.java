package net.loveruby.cflat.ast;
import net.loveruby.cflat.entity.*;
import net.loveruby.cflat.exception.SemanticError;
import java.util.*;

public class Declarations {
    Set<DefinedVariable> defvars = new LinkedHashSet<DefinedVariable>();
    Set<UndefinedVariable> vardecls = new LinkedHashSet<UndefinedVariable>();
    Set<DefinedFunction> defuns = new LinkedHashSet<DefinedFunction>();
    Set<UndefinedFunction> funcdecls = new LinkedHashSet<UndefinedFunction>();
    Set<Constant> constants = new LinkedHashSet<Constant>();
    Set<StructNode> defstructs = new LinkedHashSet<StructNode>();
    Set<UnionNode> defunions = new LinkedHashSet<UnionNode>();
    Set<TypedefNode> typedefs = new LinkedHashSet<TypedefNode>();

    public void add(Declarations decls) {
        defvars.addAll(decls.defvars);
        vardecls.addAll(decls.vardecls);
        funcdecls.addAll(decls.funcdecls);
        constants.addAll(decls.constants);
        defstructs.addAll(decls.defstructs);
        defunions.addAll(decls.defunions);
        typedefs.addAll(decls.typedefs);
    }

    // C89's "tentative definition": several file-scope declarations of
    // the same variable with no initializer -- or with only one of them
    // ever supplying one -- all refer to the *same* variable (this used
    // to let a variable declared "int x;" in a header, #included into
    // several translation units, avoid a link error; here, with headers
    // spliced into one flat token stream, the case that actually shows
    // up is the same name declared more than once in one file, e.g.
    // across taken/untaken "#ifdef" branches). Only two declarations
    // that *both* supply an initializer are a genuine conflict.
    public void addDefvar(DefinedVariable var) {
        DefinedVariable existing = findDefvar(var.name());
        if (existing == null) {
            defvars.add(var);
            return;
        }
        if (existing.hasInitializer() && var.hasInitializer()) {
            throw new SemanticError("duplicated definition: " + var.name()
                    + ": " + existing.location() + " and " + var.location());
        }
        if (var.hasInitializer()) {
            // This declaration supplies the initializer the earlier,
            // tentative one(s) never did -- it becomes the one real
            // definition.
            defvars.remove(existing);
            defvars.add(var);
        }
        // else: "existing" (whichever of the two actually has the
        // initializer, if either does) already covers it -- this
        // declaration is just another tentative re-declaration.
    }

    public void addDefvars(List<DefinedVariable> vars) {
        for (DefinedVariable var : vars) {
            addDefvar(var);
        }
    }

    private DefinedVariable findDefvar(String name) {
        for (DefinedVariable var : defvars) {
            if (var.name().equals(name)) {
                return var;
            }
        }
        return null;
    }

    public List<DefinedVariable> defvars() {
        return new ArrayList<DefinedVariable>(defvars);
    }

    public void addVardecl(UndefinedVariable var) {
        vardecls.add(var);
    }

    public List<UndefinedVariable> vardecls() {
        return new ArrayList<UndefinedVariable>(vardecls);
    }

    public void addConstant(Constant c) {
        constants.add(c);
    }

    public List<Constant> constants() {
        return new ArrayList<Constant>(constants);
    }

    public void addDefun(DefinedFunction func) {
        defuns.add(func);
    }

    public List<DefinedFunction> defuns() {
        return new ArrayList<DefinedFunction>(defuns);
    }

    public void addFuncdecl(UndefinedFunction func) {
        funcdecls.add(func);
    }

    public List<UndefinedFunction> funcdecls() {
        return new ArrayList<UndefinedFunction>(funcdecls);
    }

    public void addDefstruct(StructNode n) {
        defstructs.add(n);
    }

    public List<StructNode> defstructs() {
        return new ArrayList<StructNode>(defstructs);
    }

    public void addDefunion(UnionNode n) {
        defunions.add(n);
    }

    public List<UnionNode> defunions() {
        return new ArrayList<UnionNode>(defunions);
    }

    public void addTypedef(TypedefNode n) {
        typedefs.add(n);
    }

    public List<TypedefNode> typedefs() {
        return new ArrayList<TypedefNode>(typedefs);
    }
}
