package net.loveruby.cflat.compiler;
import net.loveruby.cflat.ast.*;
import net.loveruby.cflat.entity.*;
import net.loveruby.cflat.type.*;
import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.exception.*;
import java.util.*;

public class TypeResolver extends Visitor
        implements EntityVisitor<Void>, DeclarationVisitor<Void> {
    // #@@range/ctor{
    private final TypeTable typeTable;
    private final ErrorHandler errorHandler;

    public TypeResolver(TypeTable typeTable, ErrorHandler errorHandler) {
        this.typeTable = typeTable;
        this.errorHandler = errorHandler;
    }
    // #@@}

    // #@@range/resolveProgram{
    public void resolve(AST ast) {
        defineTypes(ast.types());
        // #@@range/resolveProgram_core{
        for (TypeDefinition t : ast.types()) {
            t.accept(this);
        }
        for (Entity e : ast.entities()) {
            e.accept(this);
        }
        // #@@}
    }
    // #@@}

    // #@@range/defineTypes{
    private void defineTypes(List<TypeDefinition> deftypes) {
        for (TypeDefinition def : deftypes) {
            if (typeTable.isDefined(def.typeRef())) {
                error(def, "duplicated type definition: " + def.typeRef());
            }
            else {
                typeTable.put(def.typeRef(), def.definingType());
            }
        }
    }
    // #@@}

    // #@@range/bindType{
    private void bindType(TypeNode n) {
        if (n.isResolved()) return;
        n.setType(typeTable.get(n.typeRef()));
    }
    // #@@}

    //
    // Declarations
    //

    // #@@range/StructNode{
    public Void visit(StructNode struct) {
        resolveCompositeType(struct);
        return null;
    }
    // #@@}

    // #@@range/UnionNode{
    public Void visit(UnionNode union) {
        resolveCompositeType(union);
        return null;
    }
    // #@@}

    // #@@range/resolveCompositeType{
    public void resolveCompositeType(CompositeTypeDefinition def) {
        CompositeType ct = (CompositeType)typeTable.get(def.typeNode().typeRef());
        if (ct == null) {
            throw new Error("cannot intern struct/union: " + def.name());
        }
        for (Slot s : ct.members()) {
            bindType(s.typeNode());
            if (s.isBitField()) {
                checkBitField(s);
            }
        }
    }
    // #@@}

    // #@@range/checkBitField{
    // C99 6.7.2.1p4/p9: a bit-field's own base type must be an integer
    // type, and its width can't exceed that type's own full bit width
    // (a run of same-typed bit-fields is later *packed* together by
    // StructType#computeOffsets(), but the field's own individual
    // width is still bounded by its declared type -- "unsigned char x
    // : 9;" makes no more sense here than in real C). Restricted to a
    // storage unit no wider than "int" (4 bytes): IRGenerator's own
    // bit-field read/write masks are built as plain 32-bit immediates,
    // matching int_t()'s own width, and the standard itself only ever
    // requires "_Bool"/"signed int"/"unsigned int" to work as a
    // bit-field's base type anyway (wider bases are a well-known GNU
    // extension, out of scope here).
    private void checkBitField(Slot s) {
        Type t = s.type();
        if (! t.isInteger()) {
            error(s, "invalid bit-field type (must be an integer type): " + t);
            return;
        }
        if (t.size() > 4) {
            error(s, "bit-field base type is too wide (must be at most 4 "
                    + "bytes): " + t);
            return;
        }
        long width = s.bitWidth();
        if (width < 0) {
            error(s, "bit-field width cannot be negative: " + width);
            return;
        }
        if (width > t.size() * 8) {
            error(s, "bit-field width (" + width + ") exceeds the width of "
                    + "its own type " + t + " (" + (t.size() * 8) + " bits)");
        }
    }
    // #@@}

    // #@@range/TypedefNode{
    public Void visit(TypedefNode typedef) {
        bindType(typedef.typeNode());
        bindType(typedef.realTypeNode());
        return null;
    }
    // #@@}

    //
    // Entities
    //

    // #@@range/DefinedVariable{
    public Void visit(DefinedVariable var) {
        bindType(var.typeNode());
        if (var.hasInitializer()) {
            visitExpr(var.initializer());
        }
        return null;
    }
    // #@@}

    public Void visit(UndefinedVariable var) {
        bindType(var.typeNode());
        return null;
    }

    public Void visit(Constant c) {
        bindType(c.typeNode());
        visitExpr(c.value());
        return null;
    }

    // #@@range/DefinedFunction{
    public Void visit(DefinedFunction func) {
        resolveFunctionHeader(func);
        visitStmt(func.body());
        return null;
    }
    // #@@}

    public Void visit(UndefinedFunction func) {
        resolveFunctionHeader(func);
        return null;
    }

    // #@@range/resolveFunctionHeader{
    private void resolveFunctionHeader(Function func) {
        bindType(func.typeNode());
        for (CBCParameter param : func.parameters()) {
            // arrays must be converted to pointers in a function parameter.
            Type t = typeTable.getParamType(param.typeNode().typeRef());
            param.typeNode().setType(t);
        }
    }
    // #@@}

    //
    // Expressions
    //

    public Void visit(BlockNode node) {
        for (DefinedVariable var : node.variables()) {
            var.accept(this);
        }
        visitStmts(node.stmts());
        return null;
    }

    public Void visit(CastNode node) {
        bindType(node.typeNode());
        super.visit(node);
        return null;
    }

    public Void visit(SizeofExprNode node) {
        bindType(node.typeNode());
        super.visit(node);
        return null;
    }

    public Void visit(SizeofTypeNode node) {
        bindType(node.operandTypeNode());
        bindType(node.typeNode());
        super.visit(node);
        return null;
    }

    public Void visit(IntegerLiteralNode node) {
        bindType(node.typeNode());
        return null;
    }

    public Void visit(FloatLiteralNode node) {
        bindType(node.typeNode());
        return null;
    }

    public Void visit(StringLiteralNode node) {
        bindType(node.typeNode());
        return null;
    }

    public Void visit(CompoundLiteralNode node) {
        bindType(node.typeNode());
        super.visit(node);
        return null;
    }

    private void error(Node node, String msg) {
        errorHandler.error(node.location(), msg);
    }
}
