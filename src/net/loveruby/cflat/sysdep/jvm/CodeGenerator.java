package net.loveruby.cflat.sysdep.jvm;

import net.loveruby.cflat.ir.*;
import net.loveruby.cflat.entity.*;
import net.loveruby.cflat.ast.Location;
import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.utils.NameUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Compiles cflat IR directly to a single JVM class file using ASM,
 * instead of x86 assembly.
 *
 * This backend only supports the "computational core" of the language:
 * integer arithmetic (char/short/int/long, signed and unsigned), control
 * flow (if/while/for/do/switch/goto), recursion and global scalar
 * variables.  There is no memory model on the JVM comparable to a C
 * address space, so pointers, arrays, structs and unions are NOT
 * supported: a pointer/function-pointer value is carried around as an
 * opaque, unusable handle (so e.g. an unused "char **argv" parameter is
 * harmless), but dereferencing one (*p, p[i], p->m, &x) is a compile
 * error reported through the normal ErrorHandler.
 *
 * Calls to functions that are not defined in the same source file are
 * rejected, except for a handful of libc intrinsics that are translated
 * to real JVM calls so simple, printf-based demo programs still work:
 * putchar(int), puts(char*) and printf(char*, ...) -- the last two only
 * when the format/string argument is a string literal.
 */
public class CodeGenerator implements net.loveruby.cflat.sysdep.CodeGenerator {
    private final ErrorHandler errorHandler;

    private String className;
    private ClassWriter cw;
    private final Map<Entity, GlobalInfo> globals = new HashMap<Entity, GlobalInfo>();

    public CodeGenerator(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    public net.loveruby.cflat.sysdep.AssemblyCode generate(IR ir) {
        className = sanitizeClassName(baseName(ir.fileName()));
        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, className, null, "java/lang/Object", null);

        for (DefinedVariable var : ir.scope().definedGlobalScopeVariables()) {
            Width w = widthOf(var.type(), var.location());
            String fieldName = sanitizeFieldName(var.symbolString());
            globals.put(var, new GlobalInfo(fieldName, w));
            int access = ACC_STATIC | (var.isPrivate() ? ACC_PRIVATE : ACC_PUBLIC);
            cw.visitField(access, fieldName, w.descriptor, null, null).visitEnd();
        }

        emitClinit(ir);

        DefinedFunction mainFunction = null;
        for (DefinedFunction f : ir.definedFunctions()) {
            if (f.name().equals("main")) {
                mainFunction = f;
            }
            compileFunction(f);
        }

        if (mainFunction != null && !errorHandler.errorOccured()) {
            emitMainBridge(mainFunction);
        }

        cw.visitEnd();
        if (errorHandler.errorOccured()) {
            // Callers check errorHandler.errorOccured() before doing
            // anything with the returned bytes, so this is never written.
            return new JVMAssemblyCode(className, new byte[0]);
        }
        return new JVMAssemblyCode(className, cw.toByteArray());
    }

    //
    // Top-level structure: class name, fields, <clinit>, main() bridge
    //

    private String baseName(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private String sanitizeClassName(String name) {
        // Must agree with SourceFile's default ".class" output name, or
        // the produced file and the class name inside it would not match
        // and "java <name>" would fail to load it.
        return NameUtils.toJavaIdentifier(name);
    }

    private String sanitizeFieldName(String name) {
        return name.replace('.', '$');
    }

    private void emitClinit(IR ir) {
        List<DefinedVariable> inits = new ArrayList<DefinedVariable>();
        for (DefinedVariable var : ir.scope().definedGlobalScopeVariables()) {
            if (var.hasInitializer() && var.ir() != null) {
                inits.add(var);
            }
        }
        if (inits.isEmpty()) return;

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        FunctionCompiler fc = new FunctionCompiler(mv);
        mv.visitCode();
        for (DefinedVariable var : inits) {
            fc.currentLocation = var.location();
            fc.compile(var.ir());
            GlobalInfo g = globals.get(var);
            mv.visitFieldInsn(PUTSTATIC, className, g.name, g.width.descriptor);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void compileFunction(DefinedFunction f) {
        if (f.type().getFunctionType().isVararg()) {
            errorHandler.error(f.location(),
                    "variadic functions are not supported by the JVM backend: "
                            + f.name() + "()");
            return;
        }
        String desc = methodDescriptor(f);
        int access = ACC_STATIC | (f.isPrivate() ? ACC_PRIVATE : ACC_PUBLIC);
        MethodVisitor mv = cw.visitMethod(access, f.name(), desc, null, null);
        FunctionCompiler fc = new FunctionCompiler(mv, f);
        fc.run();
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Emits a bridge "public static void main(String[])" that calls the
     *  user's cflat main() and translates its return value to System.exit. */
    private void emitMainBridge(DefinedFunction mainFunction) {
        List<CBCParameter> params = mainFunction.parameters();
        if (params.size() > 2) {
            errorHandler.error(mainFunction.location(),
                    "unsupported main() signature for the JVM backend "
                            + "(expected 0 or 2 parameters)");
            return;
        }
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        if (params.size() >= 1) {
            // argc = args.length + 1 (argv[0], the program name, has no
            // JVM equivalent but still counts towards argc in C)
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IADD);
            if (widthOf(params.get(0).type(), params.get(0).location()) == Width.LONG) {
                mv.visitInsn(I2L);
            }
        }
        if (params.size() >= 2) {
            // argv: cflat's "char **argv" has no real representation here;
            // pass an unusable placeholder handle (see the class comment).
            if (widthOf(params.get(1).type(), params.get(1).location()) == Width.LONG) {
                mv.visitInsn(LCONST_0);
            }
            else {
                mv.visitInsn(ICONST_0);
            }
        }
        mv.visitMethodInsn(INVOKESTATIC, className, "main",
                methodDescriptor(mainFunction), false);
        if (!mainFunction.isVoid()) {
            if (widthOf(mainFunction.returnType(), mainFunction.location()) == Width.LONG) {
                mv.visitInsn(L2I);
            }
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private String methodDescriptor(Function f) {
        StringBuilder sb = new StringBuilder("(");
        for (CBCParameter p : f.parameters()) {
            sb.append(widthOf(p.type(), p.location()).descriptor);
        }
        sb.append(")");
        sb.append(f.isVoid() ? "V" : widthOf(f.returnType(), f.location()).descriptor);
        return sb.toString();
    }

    //
    // Type widths
    //

    /** Storage width of a value on the JVM: everything that is 8 bytes
     *  wide (cflat "long", and pointers under our lp64 TypeTable) lives in
     *  a JVM long; everything else (char/short/int, and any pointer we
     *  can't otherwise represent) lives in a plain JVM int. */
    private enum Width {
        INT(1, "I"), LONG(2, "J");

        final int slots;
        final String descriptor;

        Width(int slots, String descriptor) {
            this.slots = slots;
            this.descriptor = descriptor;
        }
    }

    private Width widthOf(net.loveruby.cflat.type.Type t, Location loc) {
        if (t.isStruct() || t.isUnion() || t.isArray()) {
            errorHandler.error(loc,
                    "struct/union/array types are not supported by the JVM backend: " + t);
            return Width.INT;
        }
        if (t.isVoid()) {
            return Width.INT;
        }
        return (t.size() == 8) ? Width.LONG : Width.INT;
    }

    private static boolean isWide(net.loveruby.cflat.asm.Type t) {
        return t == net.loveruby.cflat.asm.Type.INT64;
    }

    private static class GlobalInfo {
        final String name;
        final Width width;

        GlobalInfo(String name, Width width) {
            this.name = name;
            this.width = width;
        }
    }

    //
    // Per-function / per-initializer compiler
    //

    private class FunctionCompiler implements IRVisitor<Void, Void> {
        private final MethodVisitor mv;
        private final DefinedFunction func;
        private final Map<Entity, Integer> slots = new HashMap<Entity, Integer>();
        private final Map<net.loveruby.cflat.asm.Label, org.objectweb.asm.Label> labels =
                new HashMap<net.loveruby.cflat.asm.Label, org.objectweb.asm.Label>();
        private final int switchScratchInt;
        private final int switchScratchLong;
        private Location currentLocation;

        /** For compiling a function body. */
        FunctionCompiler(MethodVisitor mv, DefinedFunction func) {
            this.mv = mv;
            this.func = func;
            this.currentLocation = func.location();
            int next = 0;
            for (CBCParameter p : func.parameters()) {
                slots.put(p, next);
                next += widthOf(p.type(), p.location()).slots;
            }
            // NOTE: func.localVariables() actually walks the parameter
            // scope too (it shares the LocalScope machinery with real
            // block scopes), so it would re-list the parameters here.
            // func.lvarScope() is the function body's own scope, which
            // correctly excludes them (this mirrors how the x86 backend
            // locates its stack frame).
            for (DefinedVariable v : func.lvarScope().allLocalVariables()) {
                slots.put(v, next);
                next += widthOf(v.type(), v.location()).slots;
            }
            switchScratchInt = next++;
            switchScratchLong = next;
        }

        /** For compiling a single top-level constant initializer expression
         *  (used from <clinit>): no parameters, no locals, no switches. */
        FunctionCompiler(MethodVisitor mv) {
            this.mv = mv;
            this.func = null;
            this.switchScratchInt = -1;
            this.switchScratchLong = -1;
        }

        void run() {
            mv.visitCode();
            for (Stmt s : func.ir()) {
                compileStmt(s);
            }
            appendFallbackReturn();
        }

        private void appendFallbackReturn() {
            // JVM bytecode must not "fall off the end" of a method; a
            // trailing return here is unreachable whenever the cflat
            // function itself already returns on every path (dead code
            // that ASM's frame computation is fine with), and gives
            // well-defined behavior otherwise, matching C's undefined
            // behavior with a plain 0.
            if (func.isVoid()) {
                mv.visitInsn(RETURN);
            }
            else if (widthOf(func.returnType(), func.location()) == Width.LONG) {
                mv.visitInsn(LCONST_0);
                mv.visitInsn(LRETURN);
            }
            else {
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
            }
        }

        private void error(String msg) {
            errorHandler.error(currentLocation, msg);
        }

        private org.objectweb.asm.Label getLabel(net.loveruby.cflat.asm.Label l) {
            org.objectweb.asm.Label result = labels.get(l);
            if (result == null) {
                result = new org.objectweb.asm.Label();
                labels.put(l, result);
            }
            return result;
        }

        private void compileStmt(Stmt s) {
            currentLocation = s.location();
            s.accept(this);
        }

        void compile(Expr e) {
            e.accept(this);
        }

        private void pushDummy(net.loveruby.cflat.asm.Type t) {
            mv.visitInsn(isWide(t) ? LCONST_0 : ICONST_0);
        }

        private void popValue(net.loveruby.cflat.asm.Type t) {
            mv.visitInsn(isWide(t) ? POP2 : POP);
        }

        private void coerceToInt(net.loveruby.cflat.asm.Type t) {
            if (isWide(t)) {
                mv.visitInsn(L2I);
            }
        }

        //
        // Statements
        //

        public Void visit(ExprStmt stmt) {
            Expr e = stmt.expr();
            compile(e);
            boolean isVoidResult = (e instanceof Call) && callIsVoid((Call) e);
            if (!isVoidResult) {
                popValue(e.type());
            }
            return null;
        }

        public Void visit(Assign node) {
            Entity target = (node.lhs() instanceof Addr) ? ((Addr) node.lhs()).entity() : null;
            compile(node.rhs());
            if (target == null) {
                error("assignment through a pointer/array/struct is not supported "
                        + "by the JVM backend");
                popValue(node.rhs().type());
                return null;
            }
            storeEntity(target, node.rhs().type());
            return null;
        }

        public Void visit(CJump node) {
            compile(node.cond());
            org.objectweb.asm.Label thenLabel = getLabel(node.thenLabel());
            org.objectweb.asm.Label elseLabel = getLabel(node.elseLabel());
            if (isWide(node.cond().type())) {
                mv.visitInsn(LCONST_0);
                mv.visitInsn(LCMP);
            }
            mv.visitJumpInsn(IFNE, thenLabel);
            mv.visitJumpInsn(GOTO, elseLabel);
            return null;
        }

        public Void visit(Jump node) {
            mv.visitJumpInsn(GOTO, getLabel(node.label()));
            return null;
        }

        public Void visit(Switch node) {
            compile(node.cond());
            boolean wide = isWide(node.cond().type());
            int scratch = wide ? switchScratchLong : switchScratchInt;
            mv.visitVarInsn(wide ? LSTORE : ISTORE, scratch);
            for (Case c : node.cases()) {
                mv.visitVarInsn(wide ? LLOAD : ILOAD, scratch);
                if (wide) {
                    mv.visitLdcInsn(c.value);
                    mv.visitInsn(LCMP);
                    mv.visitJumpInsn(IFEQ, getLabel(c.label));
                }
                else {
                    mv.visitLdcInsn((int) c.value);
                    mv.visitJumpInsn(IF_ICMPEQ, getLabel(c.label));
                }
            }
            mv.visitJumpInsn(GOTO, getLabel(node.defaultLabel()));
            return null;
        }

        public Void visit(LabelStmt node) {
            mv.visitLabel(getLabel(node.label()));
            return null;
        }

        public Void visit(Return node) {
            if (node.expr() != null) {
                compile(node.expr());
                mv.visitInsn(isWide(node.expr().type()) ? LRETURN : IRETURN);
            }
            else {
                mv.visitInsn(RETURN);
            }
            return null;
        }

        //
        // Expressions
        //

        public Void visit(Bin node) {
            Op op = node.op();
            switch (op) {
            case ADD: case SUB: case MUL: case S_DIV: case S_MOD:
            case BIT_AND: case BIT_OR: case BIT_XOR: {
                boolean wide = isWide(node.type());
                compile(node.left());
                compile(node.right());
                emitArith(op, wide);
                break;
            }
            case U_DIV: case U_MOD: {
                boolean wide = isWide(node.type());
                compile(node.left());
                compile(node.right());
                String owner = wide ? "java/lang/Long" : "java/lang/Integer";
                String desc = wide ? "(JJ)J" : "(II)I";
                String name = (op == Op.U_DIV) ? "divideUnsigned" : "remainderUnsigned";
                mv.visitMethodInsn(INVOKESTATIC, owner, name, desc, false);
                break;
            }
            case BIT_LSHIFT: case BIT_RSHIFT: case ARITH_RSHIFT: {
                boolean wide = isWide(node.type());
                compile(node.left());
                compile(node.right());
                coerceToInt(node.right().type());
                int opcode;
                switch (op) {
                case BIT_LSHIFT:   opcode = wide ? LSHL  : ISHL;  break;
                case BIT_RSHIFT:   opcode = wide ? LUSHR : IUSHR; break;
                default:           opcode = wide ? LSHR  : ISHR;  break;
                }
                mv.visitInsn(opcode);
                break;
            }
            default: {
                boolean operandWide = isWide(node.left().type());
                compile(node.left());
                compile(node.right());
                emitComparison(op, operandWide);
                break;
            }
            }
            return null;
        }

        private void emitArith(Op op, boolean wide) {
            int opcode;
            switch (op) {
            case ADD:     opcode = wide ? LADD : IADD; break;
            case SUB:     opcode = wide ? LSUB : ISUB; break;
            case MUL:     opcode = wide ? LMUL : IMUL; break;
            case S_DIV:   opcode = wide ? LDIV : IDIV; break;
            case S_MOD:   opcode = wide ? LREM : IREM; break;
            case BIT_AND: opcode = wide ? LAND : IAND; break;
            case BIT_OR:  opcode = wide ? LOR  : IOR;  break;
            case BIT_XOR: opcode = wide ? LXOR : IXOR; break;
            default: throw new Error("unreachable: " + op);
            }
            mv.visitInsn(opcode);
        }

        private void emitComparison(Op op, boolean wide) {
            org.objectweb.asm.Label trueLabel = new org.objectweb.asm.Label();
            org.objectweb.asm.Label endLabel = new org.objectweb.asm.Label();
            int jumpOpcode;
            if (!wide) {
                switch (op) {
                case EQ:     jumpOpcode = IF_ICMPEQ; break;
                case NEQ:    jumpOpcode = IF_ICMPNE; break;
                case S_GT:   jumpOpcode = IF_ICMPGT; break;
                case S_GTEQ: jumpOpcode = IF_ICMPGE; break;
                case S_LT:   jumpOpcode = IF_ICMPLT; break;
                case S_LTEQ: jumpOpcode = IF_ICMPLE; break;
                case U_GT: case U_GTEQ: case U_LT: case U_LTEQ:
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
                            "compareUnsigned", "(II)I", false);
                    jumpOpcode = unsignedZeroOpcode(op);
                    break;
                default: throw new Error("unknown comparison: " + op);
                }
            }
            else {
                switch (op) {
                case EQ: case NEQ: case S_GT: case S_GTEQ: case S_LT: case S_LTEQ:
                    mv.visitInsn(LCMP);
                    jumpOpcode = signedZeroOpcode(op);
                    break;
                case U_GT: case U_GTEQ: case U_LT: case U_LTEQ:
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",
                            "compareUnsigned", "(JJ)I", false);
                    jumpOpcode = unsignedZeroOpcode(op);
                    break;
                default: throw new Error("unknown comparison: " + op);
                }
            }
            mv.visitJumpInsn(jumpOpcode, trueLabel);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, endLabel);
            mv.visitLabel(trueLabel);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(endLabel);
        }

        private int signedZeroOpcode(Op op) {
            switch (op) {
            case EQ:     return IFEQ;
            case NEQ:    return IFNE;
            case S_GT:   return IFGT;
            case S_GTEQ: return IFGE;
            case S_LT:   return IFLT;
            case S_LTEQ: return IFLE;
            default: throw new Error("unreachable: " + op);
            }
        }

        private int unsignedZeroOpcode(Op op) {
            switch (op) {
            case U_GT:   return IFGT;
            case U_GTEQ: return IFGE;
            case U_LT:   return IFLT;
            case U_LTEQ: return IFLE;
            default: throw new Error("unreachable: " + op);
            }
        }

        public Void visit(Uni node) {
            switch (node.op()) {
            case UMINUS: {
                boolean wide = isWide(node.type());
                compile(node.expr());
                mv.visitInsn(wide ? LNEG : INEG);
                break;
            }
            case BIT_NOT: {
                boolean wide = isWide(node.type());
                compile(node.expr());
                if (wide) {
                    mv.visitLdcInsn(-1L);
                    mv.visitInsn(LXOR);
                }
                else {
                    mv.visitLdcInsn(-1);
                    mv.visitInsn(IXOR);
                }
                break;
            }
            case NOT: {
                boolean wide = isWide(node.expr().type());
                compile(node.expr());
                org.objectweb.asm.Label trueLabel = new org.objectweb.asm.Label();
                org.objectweb.asm.Label endLabel = new org.objectweb.asm.Label();
                if (wide) {
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LCMP);
                }
                mv.visitJumpInsn(IFEQ, trueLabel);
                mv.visitInsn(ICONST_0);
                mv.visitJumpInsn(GOTO, endLabel);
                mv.visitLabel(trueLabel);
                mv.visitInsn(ICONST_1);
                mv.visitLabel(endLabel);
                break;
            }
            case S_CAST: case U_CAST:
                compileCast(node);
                break;
            default:
                throw new Error("unknown unary operator: " + node.op());
            }
            return null;
        }

        private void compileCast(Uni node) {
            compile(node.expr());
            int srcSize = node.expr().type().size();
            int dstSize = node.type().size();
            boolean srcSigned = (node.op() == Op.S_CAST);
            if (srcSize == dstSize) {
                return;
            }
            if (srcSize <= 4 && dstSize <= 4) {
                if (dstSize < srcSize) {
                    truncateInt(dstSize, srcSigned);
                }
                // widening within <=4 bytes is a no-op: narrow values are
                // always kept fully sign/zero-extended to fill the 32-bit
                // JVM int that holds them.
                return;
            }
            if (srcSize <= 4 && dstSize == 8) {
                if (srcSigned) {
                    mv.visitInsn(I2L);
                }
                else if (srcSize == 4) {
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
                            "toUnsignedLong", "(I)J", false);
                }
                else {
                    // top bits are already 0 for a <32-bit unsigned value
                    mv.visitInsn(I2L);
                }
                return;
            }
            if (srcSize == 8 && dstSize <= 4) {
                mv.visitInsn(L2I);
                if (dstSize < 4) {
                    truncateInt(dstSize, srcSigned);
                }
                return;
            }
        }

        private void truncateInt(int width, boolean signed) {
            if (width == 1) {
                if (signed) {
                    mv.visitInsn(I2B);
                }
                else {
                    mv.visitLdcInsn(0xFF);
                    mv.visitInsn(IAND);
                }
            }
            else if (width == 2) {
                if (signed) {
                    mv.visitInsn(I2S);
                }
                else {
                    mv.visitLdcInsn(0xFFFF);
                    mv.visitInsn(IAND);
                }
            }
        }

        public Void visit(Call node) {
            compileCall(node);
            return null;
        }

        private boolean callIsVoid(Call call) {
            if (!call.isStaticCall()) return false;
            Function f = call.function();
            if (f instanceof UndefinedFunction) {
                String name = f.name();
                if (name.equals("puts") || name.equals("printf")) return true;
                if (name.equals("putchar")) return false;
                return f.isVoid();
            }
            return f.isVoid();
        }

        private void compileCall(Call node) {
            if (!node.isStaticCall()) {
                error("indirect (function pointer) calls are not supported by the JVM backend");
                pushDummy(node.type());
                return;
            }
            Function f = node.function();
            if (f instanceof UndefinedFunction) {
                String name = f.name();
                if (name.equals("putchar") && node.args().size() == 1) {
                    compilePutchar(node);
                    return;
                }
                if (name.equals("puts") && node.args().size() == 1) {
                    compilePuts(node);
                    return;
                }
                if (name.equals("printf") && !node.args().isEmpty()) {
                    compilePrintf(node);
                    return;
                }
                error("call to an external/undefined function is not supported "
                        + "by the JVM backend: " + name + "()");
                if (!f.isVoid()) {
                    pushDummy(node.type());
                }
                return;
            }
            DefinedFunction df = (DefinedFunction) f;
            for (Expr arg : node.args()) {
                compile(arg);
            }
            mv.visitMethodInsn(INVOKESTATIC, className, df.name(),
                    methodDescriptor(df), false);
        }

        private void loadSystemOut() {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        }

        private void compilePutchar(Call node) {
            Expr arg = node.args().get(0);
            loadSystemOut();
            compile(arg);
            coerceToInt(arg.type());
            mv.visitInsn(DUP_X1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "write", "(I)V", false);
        }

        private void compilePuts(Call node) {
            Expr arg = node.args().get(0);
            if (!(arg instanceof Str)) {
                error("puts() is only supported with a string literal argument "
                        + "by the JVM backend");
                return;
            }
            loadSystemOut();
            mv.visitLdcInsn(((Str) arg).entry().value());
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println",
                    "(Ljava/lang/String;)V", false);
        }

        private void compilePrintf(Call node) {
            List<Expr> args = node.args();
            Expr fmtExpr = args.get(0);
            if (!(fmtExpr instanceof Str)) {
                error("printf() is only supported with a string literal format "
                        + "by the JVM backend");
                return;
            }
            String fmt = ((Str) fmtExpr).entry().value();
            int argIndex = 1;
            StringBuilder literal = new StringBuilder();
            int i = 0;
            while (i < fmt.length()) {
                char c = fmt.charAt(i);
                if (c != '%') {
                    literal.append(c);
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < fmt.length() && (fmt.charAt(j) == 'l' || fmt.charAt(j) == 'h')) {
                    j++;
                }
                if (j >= fmt.length()) {
                    literal.append(c);
                    i++;
                    continue;
                }
                char spec = fmt.charAt(j);
                switch (spec) {
                case '%':
                    literal.append('%');
                    break;
                case 'd': case 'i': case 'u':
                    flushLiteral(literal);
                    argIndex = emitPrintArg(args, argIndex, spec == 'u');
                    break;
                case 'c':
                    flushLiteral(literal);
                    argIndex = emitPrintChar(args, argIndex);
                    break;
                case 's':
                    flushLiteral(literal);
                    argIndex = emitPrintString(args, argIndex);
                    break;
                default:
                    error("unsupported printf format specifier by the JVM backend: %" + spec);
                    return;
                }
                i = j + 1;
            }
            flushLiteral(literal);
        }

        private void flushLiteral(StringBuilder sb) {
            if (sb.length() > 0) {
                loadSystemOut();
                mv.visitLdcInsn(sb.toString());
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print",
                        "(Ljava/lang/String;)V", false);
                sb.setLength(0);
            }
        }

        private int emitPrintArg(List<Expr> args, int idx, boolean unsigned) {
            if (idx >= args.size()) {
                error("not enough arguments for printf format");
                return idx;
            }
            Expr arg = args.get(idx);
            loadSystemOut();
            compile(arg);
            boolean wide = isWide(arg.type());
            if (unsigned) {
                if (wide) {
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",
                            "toUnsignedString", "(J)Ljava/lang/String;", false);
                }
                else {
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
                            "toUnsignedString", "(I)Ljava/lang/String;", false);
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print",
                        "(Ljava/lang/String;)V", false);
            }
            else {
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print",
                        wide ? "(J)V" : "(I)V", false);
            }
            return idx + 1;
        }

        private int emitPrintChar(List<Expr> args, int idx) {
            if (idx >= args.size()) {
                error("not enough arguments for printf format");
                return idx;
            }
            Expr arg = args.get(idx);
            loadSystemOut();
            compile(arg);
            coerceToInt(arg.type());
            mv.visitInsn(I2C);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(C)V", false);
            return idx + 1;
        }

        private int emitPrintString(List<Expr> args, int idx) {
            if (idx >= args.size()) {
                error("not enough arguments for printf format");
                return idx;
            }
            Expr arg = args.get(idx);
            if (!(arg instanceof Str)) {
                error("printf %s is only supported with a string literal argument "
                        + "by the JVM backend");
                return idx + 1;
            }
            loadSystemOut();
            mv.visitLdcInsn(((Str) arg).entry().value());
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print",
                    "(Ljava/lang/String;)V", false);
            return idx + 1;
        }

        public Void visit(Addr node) {
            error("taking the address of a variable is not supported by the JVM backend");
            pushDummy(node.type());
            return null;
        }

        public Void visit(Mem node) {
            error("pointer dereference is not supported by the JVM backend");
            pushDummy(node.type());
            return null;
        }

        public Void visit(Var node) {
            Entity e = node.entity();
            Integer slot = slots.get(e);
            if (slot != null) {
                mv.visitVarInsn(isWide(node.type()) ? LLOAD : ILOAD, slot);
                return null;
            }
            GlobalInfo g = globals.get(e);
            if (g != null) {
                mv.visitFieldInsn(GETSTATIC, className, g.name, g.width.descriptor);
                return null;
            }
            error("reference to an unsupported/external variable: " + e.name());
            pushDummy(node.type());
            return null;
        }

        public Void visit(Int node) {
            if (isWide(node.type())) {
                mv.visitLdcInsn(node.value());
            }
            else {
                mv.visitLdcInsn((int) node.value());
            }
            return null;
        }

        public Void visit(Str node) {
            error("string literals are only supported as direct arguments to "
                    + "putchar/puts/printf by the JVM backend");
            pushDummy(node.type());
            return null;
        }

        private void storeEntity(Entity e, net.loveruby.cflat.asm.Type valueType) {
            // Compound assignment (i <<= 1) and ++/-- build their new value
            // as a plain Bin at the narrow declared width without going
            // through TypeChecker's usual implicit-cast machinery, so the
            // raw 32-bit JVM computation is not yet wrapped/truncated to
            // that width; normalize it here using the *variable's* own
            // declared signedness (a plain "=" assignment is already
            // correctly cast by the front-end, so this is a no-op there).
            Width w = widthOf(e.type(), e.location());
            if (w == Width.INT && e.type().isInteger()) {
                int size = (int) e.type().size();
                if (size < 4) {
                    truncateInt(size, e.type().isSigned());
                }
            }
            Integer slot = slots.get(e);
            if (slot != null) {
                mv.visitVarInsn(w == Width.LONG ? LSTORE : ISTORE, slot);
                return;
            }
            GlobalInfo g = globals.get(e);
            if (g != null) {
                mv.visitFieldInsn(PUTSTATIC, className, g.name, g.width.descriptor);
                return;
            }
            error("assignment to an unsupported/external variable: " + e.name());
            popValue(valueType);
        }
    }
}
