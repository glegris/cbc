package net.loveruby.cflat.compiler;
import net.loveruby.cflat.ast.*;
import net.loveruby.cflat.entity.*;
import net.loveruby.cflat.type.*;
import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.exception.*;
import java.util.*;

class TypeChecker extends Visitor {
    private final TypeTable typeTable;
    private final ErrorHandler errorHandler;

    // #@@range/ctor{
    public TypeChecker(TypeTable typeTable, ErrorHandler errorHandler) {
        this.typeTable = typeTable;
        this.errorHandler = errorHandler;
    }
    // #@@}

    private void check(StmtNode node) {
        visitStmt(node);
    }

    private void check(ExprNode node) {
        visitExpr(node);
    }

    // #@@range/check_AST{
    DefinedFunction currentFunction;

    public void check(AST ast) throws SemanticException {
        for (DefinedVariable var : ast.definedVariables()) {
            checkVariable(var);
        }
        for (DefinedFunction f : ast.definedFunctions()) {
            currentFunction = f;
            checkReturnType(f);
            checkParamTypes(f);
            check(f.body());
        }
        if (errorHandler.errorOccured()) {
            throw new SemanticException("compile failed.");
        }
    }
    // #@@}

    private void checkReturnType(DefinedFunction f) {
        if (isInvalidReturnType(f.returnType())) {
            error(f.location(), "returns invalid type: " + f.returnType());
        }
    }

    private void checkParamTypes(DefinedFunction f) {
        for (CBCParameter param : f.parameters()) {
            if (isInvalidParameterType(param.type())) {
                error(param.location(),
                        "invalid parameter type: " + param.type());
            }
        }
    }

    //
    // Statements
    //

    public Void visit(BlockNode node) {
        for (DefinedVariable var : node.variables()) {
            checkVariable(var);
        }
        for (StmtNode n : node.stmts()) {
            check(n);
        }
        return null;
    }

    private void checkVariable(DefinedVariable var) {
        if (isInvalidVariableType(var.type())) {
            error(var.location(), "invalid variable type");
            return;
        }
        if (var.hasInitializer()) {
            if (var.initializer() instanceof CompoundLiteralNode) {
                // "T x = (T){...};" is just "T x = {...};" with a
                // redundant type annotation -- unwrap it so the exact
                // same array/struct/union initializer lowering below
                // handles it, instead of going through a hidden
                // temporary plus a whole-object copy (which plain array
                // assignment doesn't even support).
                CompoundLiteralNode lit = (CompoundLiteralNode) var.initializer();
                if (! lit.type().isSameType(var.type())) {
                    error(var.location(), "cannot initialize " + var.type()
                            + " with a compound literal of type " + lit.type());
                    return;
                }
                var.setInitializer(lit.literal());
            }
            if (var.initializer() instanceof AggregateLiteralNode) {
                // A brace initializer is fine for an array/struct/union
                // (isInvalidLHSType would otherwise reject an array
                // here, since a *plain* "arr = ..." assignment isn't
                // allowed -- but whole-array initialization is a
                // different thing, checked on its own below).
                checkAggregateLiteral(var.type(), (AggregateLiteralNode) var.initializer());
                return;
            }
            if (isInvalidLHSType(var.type())) {
                error(var.location(), "invalid LHS type: " + var.type());
                return;
            }
            check(var.initializer());
            var.setInitializer(implicitCast(var.type(), var.initializer()));
        }
    }

    /** Checks a brace initializer ("{ e0, e1, ... }") against the type it
     *  is initializing, casting each leaf element in place (mutating the
     *  AggregateLiteralNode's own element list) the same way a plain
     *  scalar initializer is cast in checkVariable. Recurses for a
     *  nested brace list (a struct/array member that is itself an
     *  array/struct/union).
     *
     *  Scope note: the target array must have an explicit length --
     *  inferring it from the initializer list's size (like C's "int
     *  a[] = {1,2,3};") isn't supported, since by the time TypeChecker
     *  runs, TypeResolver has already bound the variable's type and
     *  there is no clean way to go back and resize it.
     *
     *  A ".member"/"[index]" designator (see AggregateLiteralNode) sets
     *  where an element lands instead of the usual "right after the
     *  previous element"; resolvePositions() does that resolution, this
     *  method only validates the result (in bounds, member exists, right
     *  kind of designator for an array vs. a struct/union) and casts
     *  each element same as before.
     */
    private void checkAggregateLiteral(Type targetType, AggregateLiteralNode lit) {
        lit.setType(targetType);
        List<ExprNode> elems = lit.elements();
        if (targetType.isArray()) {
            ArrayType at = targetType.getArrayType();
            if (! at.isAllocatedArray()) {
                error(lit, "array must have an explicit size to use a brace "
                        + "initializer (inferring it from the initializer list "
                        + "is not supported): " + targetType);
                return;
            }
            List<Integer> positions = lit.resolvePositions(null);
            for (int i = 0; i < elems.size(); i++) {
                if (lit.members().get(i) != null) {
                    error(elems.get(i), "cannot use a \".member\" designator to "
                            + "initialize an array: " + targetType);
                    continue;
                }
                int pos = positions.get(i);
                if (pos < 0 || pos >= at.length()) {
                    error(elems.get(i), "array index designator out of bounds for "
                            + targetType + ": [" + pos + "]");
                    continue;
                }
                checkAggregateElement(at.baseType(), elems, i);
            }
        }
        else if (targetType.isStruct() || targetType.isUnion()) {
            final List<Slot> members = targetType.getCompositeType().members();
            List<Integer> positions = lit.resolvePositions(
                    new AggregateLiteralNode.MemberIndexOf() {
                        public int indexOf(String name) {
                            for (int j = 0; j < members.size(); j++) {
                                if (members.get(j).name().equals(name)) return j;
                            }
                            return -1;
                        }
                    });
            int usedCount = 0;
            for (int i = 0; i < elems.size(); i++) {
                if (lit.indices().get(i) != null) {
                    error(elems.get(i), "cannot use a \"[index]\" designator to "
                            + "initialize " + targetType);
                    continue;
                }
                int pos = positions.get(i);
                if (pos < 0) {
                    error(elems.get(i), "no such member in " + targetType + ": "
                            + lit.members().get(i));
                    continue;
                }
                if (pos >= members.size()) {
                    error(elems.get(i), "too many initializers for " + targetType
                            + ": expected at most " + members.size());
                    continue;
                }
                checkAggregateElement(members.get(pos).type(), elems, i);
                usedCount++;
            }
            if (targetType.isUnion() && usedCount > 1) {
                error(lit, "too many initializers for union " + targetType
                        + ": a union initializer sets only one member");
            }
        }
        else if (elems.size() == 1) {
            // A scalar may be initialized with a single-element brace
            // list too ("int x = {5};" is legal C, equivalent to
            // "int x = 5;").
            checkAggregateElement(targetType, elems, 0);
        }
        else {
            error(lit, "cannot use a " + elems.size() + "-element brace initializer "
                    + "for " + targetType);
        }
    }

    private void checkAggregateElement(Type targetType, List<ExprNode> elems, int i) {
        ExprNode elem = elems.get(i);
        if (elem instanceof AggregateLiteralNode) {
            checkAggregateLiteral(targetType, (AggregateLiteralNode) elem);
            return;
        }
        check(elem);
        if (! checkRHS(elem)) return;
        elems.set(i, implicitCast(targetType, elem));
    }

    public Void visit(ExprStmtNode node) {
        check(node.expr());
        if (isInvalidStatementType(node.expr().type())) {
            error(node, "invalid statement type: " + node.expr().type());
            return null;
        }
        return null;
    }

    public Void visit(IfNode node) {
        super.visit(node);
        checkCond(node.cond());
        return null;
    }

    public Void visit(WhileNode node) {
        super.visit(node);
        checkCond(node.cond());
        return null;
    }

    public Void visit(ForNode node) {
        super.visit(node);
        checkCond(node.cond());
        return null;
    }

    private void checkCond(ExprNode cond) {
        mustBeScalar(cond, "condition expression");
    }

    public Void visit(SwitchNode node) {
        super.visit(node);
        mustBeInteger(node.cond(), "condition expression");
        return null;
    }

    public Void visit(ReturnNode node) {
        super.visit(node);
        if (currentFunction.isVoid()) {
            if (node.expr() != null) {
                error(node, "returning value from void function");
            }
        }
        else {  // non-void function
            if (node.expr() == null) {
                error(node, "missing return value");
                return null;
            }
            if (node.expr().type().isVoid()) {
                error(node, "returning void");
                return null;
            }
            node.setExpr(implicitCast(currentFunction.returnType(),
                                      node.expr()));
        }
        return null;
    }

    //
    // Assignment Expressions
    //

    public Void visit(AssignNode node) {
        super.visit(node);
        if (! checkLHS(node.lhs())) return null;
        if (! checkRHS(node.rhs())) return null;
        node.setRHS(implicitCast(node.lhs().type(), node.rhs()));
        return null;
    }

    public Void visit(OpAssignNode node) {
        super.visit(node);
        if (! checkLHS(node.lhs())) return null;
        if (! checkRHS(node.rhs())) return null;
        if (node.operator().equals("+") || node.operator().equals("-")) {
            if (node.lhs().type().isPointer()) {
                mustBeInteger(node.rhs(), node.operator());
                node.setRHS(integralPromotedExpr(node.rhs()));
                return null;
            }
        }
        if (! mustBeInteger(node.lhs(), node.operator())) return null;
        if (! mustBeInteger(node.rhs(), node.operator())) return null;
        Type l = integralPromotion(node.lhs().type());
        Type r = integralPromotion(node.rhs().type());
        Type opType = usualArithmeticConversion(l, r);
        if (! opType.isCompatible(l)
                && ! isSafeIntegerCast(node.rhs(), opType)) {
            warn(node, "incompatible implicit cast from "
                       + opType + " to " + l);
        }
        if (! r.isSameType(opType)) {
            // cast RHS
            node.setRHS(new CastNode(opType, node.rhs()));
        }
        return null;
    }

    /** allow safe implicit cast from integer literal like:
     *
     *    char c = 0;
     *
     *  "0" has a type integer, but we can cast (int)0 to (char)0 safely.
     */
    private boolean isSafeIntegerCast(Node node, Type type) {
        if (! type.isInteger()) return false;
        IntegerType t = (IntegerType)type;
        if (! (node instanceof IntegerLiteralNode)) return false;
        IntegerLiteralNode n = (IntegerLiteralNode)node;
        return t.isInDomain(n.value());
    }

    private boolean checkLHS(ExprNode lhs) {
        if (lhs.isParameter()) {
            // parameter is always assignable.
            return true;
        }
        else if (isInvalidLHSType(lhs.type())) {
            error(lhs, "invalid LHS expression type: " + lhs.type());
            return false;
        }
        else if (lhs.type().isConst()) {
            error(lhs, "cannot assign to a const-qualified value: " + lhs.type());
            return false;
        }
        return true;
    }

    //
    // Expressions
    //

    public Void visit(CondExprNode node) {
        super.visit(node);
        checkCond(node.cond());
        Type t = node.thenExpr().type();
        Type e = node.elseExpr().type();
        if (t.isSameType(e)) {
            return null;
        }
        else if (t.isCompatible(e)) {   // insert cast on thenBody
            node.setThenExpr(new CastNode(e, node.thenExpr()));
        }
        else if (e.isCompatible(t)) {   // insert cast on elseBody
            node.setElseExpr(new CastNode(t, node.elseExpr()));
        }
        else {
            invalidCastError(node.thenExpr(), e, t);
        }
        return null;
    }

    // #@@range/BinaryOpNode{
    public Void visit(BinaryOpNode node) {
        super.visit(node);
        if (node.operator().equals("+") || node.operator().equals("-")) {
            expectsSameArithmeticOrPointerDiff(node);
        }
        else if (node.operator().equals("*")
                || node.operator().equals("/")) {
            expectsSameArithmetic(node);
        }
        else if (node.operator().equals("%")
                || node.operator().equals("&")
                || node.operator().equals("|")
                || node.operator().equals("^")) {
            expectsSameInteger(node);
        }
        else if (node.operator().equals("<<")
                || node.operator().equals(">>")) {
            expectsShiftableIntegers(node);
        }
        else if (node.operator().equals("==")
                || node.operator().equals("!=")
                || node.operator().equals("<")
                || node.operator().equals("<=")
                || node.operator().equals(">")
                || node.operator().equals(">=")) {
            expectsComparableScalars(node);
        }
        else {
            throw new Error("unknown binary operator: " + node.operator());
        }
        return null;
    }
    // #@@}

    public Void visit(LogicalAndNode node) {
        super.visit(node);
        expectsComparableScalars(node);
        return null;
    }

    public Void visit(LogicalOrNode node) {
        super.visit(node);
        expectsComparableScalars(node);
        return null;
    }

    /**
     * For + and -, only following types of expression are valid:
     *
     *   * arithmetic (integer or float) + arithmetic
     *   * pointer + integer
     *   * integer + pointer
     *   * arithmetic - arithmetic
     *   * pointer - integer
     *   * pointer - pointer
     */
    private void expectsSameArithmeticOrPointerDiff(BinaryOpNode node) {
        if (node.left().isPointer() && node.right().isPointer()) {
            if (node.operator().equals("+")) {
                error(node, "invalid operation: pointer + pointer");
                return;
            }
            node.setType(typeTable.ptrDiffType());
        }
        else if (node.left().isPointer()) {
            mustBeInteger(node.right(), node.operator());
            // promote integer for pointer calculation
            node.setRight(integralPromotedExpr(node.right()));
            node.setType(node.left().type());
        }
        else if (node.right().isPointer()) {
            if (node.operator().equals("-")) {
                error(node, "invalid operation: integer - pointer");
                return;
            }
            mustBeInteger(node.left(), node.operator());
            // promote integer for pointer calculation
            node.setLeft(integralPromotedExpr(node.left()));
            node.setType(node.right().type());
        }
        else {
            expectsSameArithmetic(node);
        }
    }

    private ExprNode integralPromotedExpr(ExprNode expr) {
        Type t = integralPromotion(expr.type());
        if (t.isSameType(expr.type())) {
            return expr;
        }
        else {
            return new CastNode(t, expr);
        }
    }

    // %, &, |, ^
    // #@@range/expectsSameInteger{
    private void expectsSameInteger(BinaryOpNode node) {
        if (! mustBeInteger(node.left(), node.operator())) return;
        if (! mustBeInteger(node.right(), node.operator())) return;
        arithmeticImplicitCast(node);
    }
    // #@@}

    // <<, >>
    private void expectsShiftableIntegers(BinaryOpNode node) {
        if (! mustBeInteger(node.left(), node.operator())) return;
        if (! mustBeInteger(node.right(), node.operator())) return;
        shiftImplicitCast(node);
    }

    // Unlike every other binary integer operator, a shift's two
    // operands are NOT subject to "usual arithmetic conversion"
    // against each other at all (C99 6.5.7p3): "the integer promotions
    // are performed on each of the operands [independently]. The type
    // of the result is that of the promoted left operand." So
    // "(short)1 << (long long)1" has type "int" (short's own
    // promotion), not "long long", even though the right operand is
    // wider -- reusing arithmeticImplicitCast()'s "widest operand
    // wins" logic here (as an earlier version of this code did, having
    // originally treated "<<"/">>" exactly like "%"/"&"/"|"/"^", which
    // genuinely do need the two operands unified to a common type)
    // would silently widen the result to match a wider *right* operand
    // that the standard says must never influence the result's type at
    // all.
    private void shiftImplicitCast(BinaryOpNode node) {
        Type l = arithPromotion(node.left().type());
        Type r = arithPromotion(node.right().type());
        if (! node.left().type().isSameType(l)) {
            node.setLeft(new CastNode(l, node.left()));
        }
        if (! node.right().type().isSameType(r)) {
            node.setRight(new CastNode(r, node.right()));
        }
        node.setType(l);
    }

    // +, -, *, / (integer or floating)
    private void expectsSameArithmetic(BinaryOpNode node) {
        if (! mustBeArithmetic(node.left(), node.operator())) return;
        if (! mustBeArithmetic(node.right(), node.operator())) return;
        arithmeticImplicitCast(node);
    }

    // ==, !=, >, >=, <, <=, &&, ||
    private void expectsComparableScalars(BinaryOpNode node) {
        if (! mustBeScalar(node.left(), node.operator())) return;
        if (! mustBeScalar(node.right(), node.operator())) return;
        if (node.left().type().isPointer()) {
            ExprNode right = forcePointerType(node.left(), node.right());
            node.setRight(right);
            node.setType(node.left().type());
            return;
        }
        if (node.right().type().isPointer()) {
            ExprNode left = forcePointerType(node.right(), node.left());
            node.setLeft(left);
            node.setType(node.right().type());
            return;
        }
        arithmeticImplicitCast(node);
    }

    // cast slave node to master node.
    private ExprNode forcePointerType(ExprNode master, ExprNode slave) {
        if (master.type().isCompatible(slave.type())) {
            // needs no cast
            return slave;
        }
        else {
            warn(slave, "incompatible implicit cast from "
                       + slave.type() + " to " + master.type());
            return new CastNode(master.type(), slave);
        }
    }

    // Processes usual arithmetic conversion for binary operations.
    // #@@range/arithmeticImplicitCast{
    private void arithmeticImplicitCast(BinaryOpNode node) {
        Type r = arithPromotion(node.right().type());
        Type l = arithPromotion(node.left().type());
        Type target = usualArithmeticConversion(l, r);
        // Compared against each operand's own ACTUAL type below, not
        // against "l"/"r" (the PROMOTED type computed just above): a
        // below-int operand (e.g. "unsigned char") whose promotion
        // happens to land exactly on "target" (a very common case --
        // any comparison/arithmetic between a narrow unsigned type and
        // a plain "int") would otherwise skip inserting a cast
        // entirely, since "r.isSameType(target)"/"l.isSameType(target)"
        // is satisfied by the promoted TYPE alone -- leaving the
        // operand's own AST node (and the value it actually compiles
        // to) at its original, narrower width, never promoted at all,
        // while the BinaryOpNode's own type (set below) claims the
        // wider "target" width. The two sides of a "==" or an
        // arithmetic op ending up at genuinely different widths this
        // way is exactly what silently produced wrong results here
        // (e.g. "b == (unsigned char)(x + 1)" comparing a full int
        // against a value that was never actually widened to match).
        if (! node.left().type().isSameType(target)) {
            // insert cast on left expr
            node.setLeft(new CastNode(target, node.left()));
        }
        if (! node.right().type().isSameType(target)) {
            // insert cast on right expr
            node.setRight(new CastNode(target, node.right()));
        }
        node.setType(target);
    }
    // #@@}

    // +, -, !, ~
    public Void visit(UnaryOpNode node) {
        super.visit(node);
        if (node.operator().equals("!")) {
            // "!"'s own result is always a plain 0/1 "int" regardless
            // of its operand's type (any scalar, unpromoted -- it's
            // just tested for zero/nonzero, never itself computed at a
            // wider width), so unlike +/-/~ below, this needs no
            // promotion at all.
            mustBeScalar(node.expr(), node.operator());
        }
        else if (node.operator().equals("-") || node.operator().equals("+")) {
            // Unary +/- accept a float/double operand too (~ doesn't:
            // bitwise-not is integer-only).
            if (mustBeArithmetic(node.expr(), node.operator())) {
                promoteUnaryOperand(node);
            }
        }
        else {
            if (mustBeInteger(node.expr(), node.operator())) {
                promoteUnaryOperand(node);
            }
        }
        return null;
    }

    // C99 6.5.3.3p1: unary +/-/~ each apply integer promotion to their
    // operand (a no-op for +/- on a float/double operand -- see
    // arithPromotion()), and the expression's own type is that
    // promoted type. UnaryOpNode#type() just proxies its own expr's
    // type, so materializing the promotion as a cast on that expr --
    // exactly like arithmeticImplicitCast() already does for binary
    // operators, and for the identical reason: skipping the cast
    // whenever the promoted type happens to already equal the
    // *original* type would be fine, but skipping it just because
    // nothing else compares it to another value here would leave a
    // below-int operand (e.g. "-((unsigned short)x)") computed at its
    // original narrow width instead of at "int" -- is enough to fix
    // both the reported type and the actual computed value.
    private void promoteUnaryOperand(UnaryOpNode node) {
        Type t = arithPromotion(node.expr().type());
        if (! node.expr().type().isSameType(t)) {
            node.setExpr(new CastNode(t, node.expr()));
        }
    }

    // ++x, --x
    public Void visit(PrefixOpNode node) {
        super.visit(node);
        expectsScalarLHS(node);
        return null;
    }

    // x++, x--
    public Void visit(SuffixOpNode node) {
        super.visit(node);
        expectsScalarLHS(node);
        return null;
    }

    private void expectsScalarLHS(UnaryArithmeticOpNode node) {
        if (node.expr().isParameter()) {
            // parameter is always a scalar.
        }
        else if (node.expr().type().isArray()) {
            // We cannot modify non-parameter array.
            wrongTypeError(node.expr(), node.operator());
            return;
        }
        else if (node.expr().type().isConst()) {
            error(node.expr(), "cannot " + node.operator()
                    + " a const-qualified value: " + node.expr().type());
            return;
        }
        else {
            mustBeScalar(node.expr(), node.operator());
        }
        if (node.expr().type().isInteger()) {
            Type opType = integralPromotion(node.expr().type());
            if (! node.expr().type().isSameType(opType)) {
                node.setOpType(opType);
            }
            node.setAmount(1);
        }
        else if (node.expr().type().isFloat()) {
            // No promotion for floating types (see arithPromotion); the
            // amount itself is a floating 1.0, synthesized directly by
            // IRGenerator (UnaryArithmeticOpNode#amount is an integer
            // field and unused for this case).
        }
        else if (node.expr().type().isPointer()) {
            if (node.expr().type().baseType().isVoid()) {
                // We cannot increment/decrement void*
                wrongTypeError(node.expr(), node.operator());
                return;
            }
            node.setAmount(node.expr().type().baseType().size());
        }
        else {
            throw new Error("must not happen");
        }
    }

    /**
     * For EXPR(ARG), checks:
     *
     *   * The number of argument matches function prototype.
     *   * ARG matches function prototype.
     *   * ARG is neither a struct nor an union.
     */
    public Void visit(FuncallNode node) {
        super.visit(node);
        FunctionType type = node.functionType();
        if (! type.acceptsArgc(node.numArgs())) {
            error(node, "wrong number of argments: " + node.numArgs());
            return null;
        }
        Iterator<ExprNode> args = node.args().iterator();
        List<ExprNode> newArgs = new ArrayList<ExprNode>();
        // mandatory args
        for (Type param : type.paramTypes()) {
            ExprNode arg = args.next();
            newArgs.add(checkRHS(arg) ? implicitCast(param, arg) : arg);
        }
        // optional args
        while (args.hasNext()) {
            ExprNode arg = args.next();
            newArgs.add(checkRHS(arg) ? castOptionalArg(arg) : arg);
        }
        node.replaceArgs(newArgs);
        return null;
    }

    private ExprNode castOptionalArg(ExprNode arg) {
        if (! arg.type().isInteger()) {
            return arg;
        }
        Type t = arg.type().isSigned()
            ? typeTable.signedStackType()
            : typeTable.unsignedStackType();
        return arg.type().size() < t.size() ? implicitCast(t, arg) : arg;
    }

    public Void visit(ArefNode node) {
        super.visit(node);
        mustBeInteger(node.index(), "[]");
        return null;
    }

    public Void visit(CastNode node) {
        super.visit(node);
        if (! node.expr().type().isCastableTo(node.type())) {
            invalidCastError(node, node.expr().type(), node.type());
        }
        return null;
    }

    public Void visit(CompoundLiteralNode node) {
        if (isInvalidVariableType(node.type())) {
            error(node, "invalid compound literal type: " + node.type());
            return null;
        }
        // Same validation/casting a variable's own brace initializer
        // gets (array/struct/union member-by-member, or a single scalar)
        // -- a compound literal is initialized exactly the same way.
        checkAggregateLiteral(node.type(), node.literal());
        return null;
    }

    //
    // Utilities
    //

    private boolean checkRHS(ExprNode rhs) {
        if (isInvalidRHSType(rhs.type())) {
            error(rhs, "invalid RHS expression type: " + rhs.type());
            return false;
        }
        return true;
    }

    // Processes forced-implicit-cast.
    // Applied To: return expr, assignment RHS, funcall argument
    private ExprNode implicitCast(Type targetType, ExprNode expr) {
        if (expr.type().isSameType(targetType)) {
            return expr;
        }
        else if (expr.type().isCastableTo(targetType)) {
            if (! expr.type().isCompatible(targetType)
                    && ! isSafeIntegerCast(expr, targetType)) {
                warn(expr, "incompatible implicit cast from "
                           + expr.type() + " to " + targetType);
            }
            return new CastNode(targetType, expr);
        }
        else {
            invalidCastError(expr, expr.type(), targetType);
            return expr;
        }
    }

    // Like integralPromotion, but a no-op for floating types (C99 does
    // not promote float to double, or any float type to int, before an
    // arithmetic operation -- only integers below int width get promoted).
    private Type arithPromotion(Type t) {
        return t.isFloat() ? t : integralPromotion(t);
    }

    // Process integral promotion (integers only).
    // #@@range/integralPromotion{
    private Type integralPromotion(Type t) {
        if (!t.isInteger()) {
            throw new Error("integralPromotion for " + t);
        }
        Type intType = typeTable.signedInt();
        if (t.size() < intType.size()) {
            return intType;
        }
        else {
            return t;
        }
    }
    // #@@}

    // Usual arithmetic conversion, generalized over any integer size
    // (char/short/int/long/long long and their unsigned counterparts) by
    // C99's own actual rule rather than a fixed set of named type pairs:
    // Size of l, r >= sizeof(int) (for the integer case; arithPromotion
    // leaves floating types untouched, so they can be any size here).
    // #@@range/usualArithmeticConversion{
    private Type usualArithmeticConversion(Type l, Type r) {
        if (l.isFloat() || r.isFloat()) {
            return usualArithmeticConversionFloat(l, r);
        }
        // Whichever operand has the strictly wider integer *rank* (here,
        // just its size -- this project's integer types are all nested
        // char < short < int <= long <= long long, with "long long"
        // always exactly 8 bytes per C99 regardless of "long"'s own
        // width on this platform, see TypeTable) wins outright,
        // regardless of signedness: it can represent every value the
        // narrower type can, signed or not. When the two are the same
        // size (e.g. "unsigned int"/"long" on a platform where they're
        // both 4 bytes, or plain "int"/"unsigned int"), the unsigned
        // one wins instead, since a same-size signed type can't
        // represent every value its unsigned counterpart can.
        //
        // This used to be a fixed cascade of isSameType() checks against
        // only signed/unsigned int/long, silently falling through to
        // plain "int" -- discarding all but the bottom 32 bits -- for
        // any expression mixing a "long long"/"unsigned long long"
        // operand with anything else at all (even a plain "int"
        // literal), since neither was ever compared against.
        if (l.size() != r.size()) {
            return (l.size() > r.size()) ? l : r;
        }
        if (l.isSigned() != r.isSigned()) {
            return l.isSigned() ? r : l;
        }
        return l;
    }
    // #@@}

    // If either operand is floating, the other is converted to the
    // "wider" of the two floating types involved (an integer operand is
    // simply treated as narrower than any floating type here).
    private Type usualArithmeticConversionFloat(Type l, Type r) {
        Type dbl = typeTable.doubleType();
        if ((l.isFloat() && l.size() == dbl.size())
                || (r.isFloat() && r.size() == dbl.size())) {
            return dbl;
        }
        return typeTable.floatType();
    }

    private boolean isInvalidStatementType(Type t) {
        // struct/union used to be rejected here, but passing/returning
        // them by value is now supported (JVM backend only -- see
        // sysdep/jvm/CodeGenerator.java; the x86 backend rejects it at
        // code generation time instead, since it doesn't implement that
        // ABI).
        return false;
    }

    private boolean isInvalidReturnType(Type t) {
        return t.isArray();
    }

    private boolean isInvalidParameterType(Type t) {
        return t.isVoid() || t.isIncompleteArray();
    }

    private boolean isInvalidVariableType(Type t) {
        return t.isVoid() || (t.isArray() && ! t.isAllocatedArray());
    }

    private boolean isInvalidLHSType(Type t) {
        // Array is OK if it is declared as a type of parameter.
        // struct/union are now a valid (whole-value) assignment target;
        // see the isInvalidStatementType comment above.
        return t.isVoid() || t.isArray();
    }

    private boolean isInvalidRHSType(Type t) {
        return t.isVoid();
    }

    private boolean mustBeInteger(ExprNode expr, String op) {
        if (! expr.type().isInteger()) {
            wrongTypeError(expr, op);
            return false;
        }
        return true;
    }

    private boolean mustBeArithmetic(ExprNode expr, String op) {
        if (! expr.type().isInteger() && ! expr.type().isFloat()) {
            wrongTypeError(expr, op);
            return false;
        }
        return true;
    }

    private boolean mustBeScalar(ExprNode expr, String op) {
        if (! expr.type().isScalar()) {
            wrongTypeError(expr, op);
            return false;
        }
        return true;
    }

    private void invalidCastError(Node n, Type l, Type r) {
        error(n, "invalid cast from " + l + " to " + r);
    }

    private void wrongTypeError(ExprNode expr, String op) {
        error(expr, "wrong operand type for " + op + ": " + expr.type());
    }

    private void warn(Node n, String msg) {
        errorHandler.warn(n.location(), msg);
    }

    private void error(Node n, String msg) {
        errorHandler.error(n.location(), msg);
    }

    private void error(Location loc, String msg) {
        errorHandler.error(loc, msg);
    }
}
