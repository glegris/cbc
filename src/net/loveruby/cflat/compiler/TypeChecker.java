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
     *  there is no clean way to go back and resize it. Designated
     *  initializers (".field = x", "[i] = x") aren't supported either:
     *  elements always map to array indices / struct members in order.
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
            if (elems.size() > at.length()) {
                error(lit, "too many initializers for " + targetType + ": expected "
                        + "at most " + at.length() + ", got " + elems.size());
                return;
            }
            for (int i = 0; i < elems.size(); i++) {
                checkAggregateElement(at.baseType(), elems, i);
            }
        }
        else if (targetType.isStruct() || targetType.isUnion()) {
            List<Slot> members = targetType.getCompositeType().members();
            if (targetType.isUnion() && elems.size() > 1) {
                error(lit, "too many initializers for union " + targetType
                        + ": a union initializer sets only its first member");
                return;
            }
            if (elems.size() > members.size()) {
                error(lit, "too many initializers for " + targetType + ": expected "
                        + "at most " + members.size() + ", got " + elems.size());
                return;
            }
            for (int i = 0; i < elems.size(); i++) {
                checkAggregateElement(members.get(i).type(), elems, i);
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
                || node.operator().equals("^")
                || node.operator().equals("<<")
                || node.operator().equals(">>")) {
            expectsSameInteger(node);
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

    // %, &, |, ^, <<, >>
    // #@@range/expectsSameInteger{
    private void expectsSameInteger(BinaryOpNode node) {
        if (! mustBeInteger(node.left(), node.operator())) return;
        if (! mustBeInteger(node.right(), node.operator())) return;
        arithmeticImplicitCast(node);
    }
    // #@@}

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
        if (! l.isSameType(target)) {
            // insert cast on left expr
            node.setLeft(new CastNode(target, node.left()));
        }
        if (! r.isSameType(target)) {
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
            mustBeScalar(node.expr(), node.operator());
        }
        else if (node.operator().equals("-") || node.operator().equals("+")) {
            // Unary +/- accept a float/double operand too (~ doesn't:
            // bitwise-not is integer-only).
            mustBeArithmetic(node.expr(), node.operator());
        }
        else {
            mustBeInteger(node.expr(), node.operator());
        }
        return null;
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

    // Usual arithmetic conversion for ILP32 platform.
    // Size of l, r >= sizeof(int) (for the integer case; arithPromotion
    // leaves floating types untouched, so they can be any size here).
    // #@@range/usualArithmeticConversion{
    private Type usualArithmeticConversion(Type l, Type r) {
        if (l.isFloat() || r.isFloat()) {
            return usualArithmeticConversionFloat(l, r);
        }
        Type s_int = typeTable.signedInt();
        Type u_int = typeTable.unsignedInt();
        Type s_long = typeTable.signedLong();
        Type u_long = typeTable.unsignedLong();
        if (    (l.isSameType(u_int) && r.isSameType(s_long))
             || (r.isSameType(u_int) && l.isSameType(s_long))) {
            return u_long;
        }
        else if (l.isSameType(u_long) || r.isSameType(u_long)) {
            return u_long;
        }
        else if (l.isSameType(s_long) || r.isSameType(s_long)) {
            return s_long;
        }
        else if (l.isSameType(u_int)  || r.isSameType(u_int)) {
            return u_int;
        }
        else {
            return s_int;
        }
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
