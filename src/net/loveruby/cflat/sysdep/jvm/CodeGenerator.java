package net.loveruby.cflat.sysdep.jvm;

import net.loveruby.cflat.ir.*;
import net.loveruby.cflat.entity.*;
import net.loveruby.cflat.ast.Location;
import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.utils.NameUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.objectweb.asm.Opcodes.*;

/**
 * Compiles cflat IR directly to a single JVM class file using ASM,
 * instead of x86 assembly.
 *
 * The JVM has no C-style flat address space, so this backend builds one:
 * a single "$mem" byte array (wrapped in a little-endian ByteBuffer,
 * "$buf") simulates the whole process memory.  Every global/static
 * variable and every string literal gets a fixed offset into it, computed
 * at compile time; every function gets its own "stack frame" (a region
 * bump-allocated from "$sp" on entry and released on every return),
 * exactly mirroring how the x86 backend lays out its own stack frame,
 * just against a simulated address space instead of a real one. A
 * variable's address is simply frameBase+offset (locals/params) or a
 * compile-time constant (globals/string literals); "&x", "*p", "p[i]",
 * "p->m" and pointer arithmetic all fall out of the IR's existing
 * Addr/Mem/Bin lowering once these primitives exist, with no further
 * special-casing needed. A tiny bump-allocated heap arena ("$hp") backs
 * argv construction (see emitMainBridge); there is no free().
 *
 * struct/union BY VALUE (as a parameter, a return value, or the whole
 * target of an assignment) is supported through a hidden-pointer
 * convention: a struct/union parameter's actual JVM slot is the address
 * of a fresh copy the *caller* makes before the call (so the callee
 * can't observe changes back in the caller, matching C's by-value
 * semantics); a struct/union-returning function gets one extra trailing
 * "long" parameter -- the address to write its result into, which it
 * also returns for convenience -- supplied by the caller as either its
 * assignment target's own address (avoiding a redundant copy) or a
 * freshly bump-allocated scratch buffer. (TypeChecker now allows this
 * everywhere, but the x86 backend does not implement this ABI and
 * rejects it at code generation time instead.) Only a *named* struct/
 * union variable is accepted in these by-value positions (as the source
 * of a copy, an argument, or a return expression); a struct/union
 * produced through a more complex expression (e.g. dereferencing a
 * pointer, "*p") is rejected with a clear error rather than silently
 * mishandled, since nothing downstream of IRGenerator can tell such an
 * expression apart from an ordinary pointer-sized value -- assign it to
 * a plain variable first.
 *
 * Function pointers are supported for functions defined in this same
 * file: taking one's address (&f, or a bare function name used as a
 * value) resolves to a small compile-time-assigned id, and calling
 * through one dispatches through a generated lookup-switch method (one
 * per distinct signature) that maps that id back to a real invokestatic
 * -- there's no such thing as a raw callable address on the JVM. This
 * does not extend to the three libc intrinsics below (they have no real
 * method to jump to) or to any other external/undefined function. Only
 * calling through a plain function-pointer variable is supported (not a
 * more complex expression, e.g. an array element or a struct member),
 * for the same reason as above.
 *
 * Calling a function that isn't defined in the same source file is
 * rejected, except for three libc intrinsics translated to real JVM
 * calls so printf-based programs work: putchar(int), puts(char*) and
 * printf(char*, ...) -- the format string itself must still be a
 * compile-time string literal, but puts/%s now accept any char*
 * expression (read from memory at run time, not just literals).
 * Finally, this backend maps cflat's "long" and every pointer type to a
 * real 8-byte JVM long (see JVMPlatform's lp64 TypeTable), so sizeof(long)
 * and sizeof(T*) are 8 here versus 4 on the (32-bit-only) x86 backend.
 */
public class CodeGenerator implements net.loveruby.cflat.sysdep.CodeGenerator {
    private static final int HEAP_SIZE = 8 * 1024 * 1024;  // 8MB simulated address space
    private static final int STATIC_BASE = 8;  // address 0 (NULL) is never a valid variable address

    private static final String MEM_FIELD = "$mem";
    private static final String BUF_FIELD = "$buf";
    private static final String SP_FIELD = "$sp";
    private static final String HP_FIELD = "$hp";
    private static final String STR_METHOD = "$str";
    private static final String NEWSTR_METHOD = "$newstr";
    private static final String ALLOC_METHOD = "$alloc";
    private static final String BUF_DESC = "Ljava/nio/ByteBuffer;";
    private static final String BUF_CLASS = "java/nio/ByteBuffer";

    private final ErrorHandler errorHandler;

    private String className;
    private ClassWriter cw;

    // Static memory layout, computed once up front.
    private final Map<Entity, Long> globalAddr = new HashMap<Entity, Long>();
    private final Map<ConstantEntry, Long> stringAddr = new HashMap<ConstantEntry, Long>();
    private final List<DefinedVariable> globalVarsInOrder = new ArrayList<DefinedVariable>();
    private final List<ConstantEntry> stringLiteralsInOrder = new ArrayList<ConstantEntry>();
    private long staticEnd;

    // Function pointers: every non-vararg defined function gets a small
    // id (its "address"); functions sharing a descriptor share a
    // dispatcher method that maps an id back to a real invokestatic.
    private final Map<DefinedFunction, Long> functionId = new HashMap<DefinedFunction, Long>();
    private final Map<String, List<DefinedFunction>> functionsByDescriptor =
            new HashMap<String, List<DefinedFunction>>();
    private long nextFunctionId = 1;  // 0 means "no function" (NULL)

    public CodeGenerator(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    public net.loveruby.cflat.sysdep.AssemblyCode generate(IR ir) {
        className = NameUtils.toJavaIdentifier(baseName(ir.fileName()));
        computeStaticLayout(ir);
        assignFunctionIds(ir);

        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, className, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, MEM_FIELD, "[B", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, BUF_FIELD, BUF_DESC, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, SP_FIELD, "J", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, HP_FIELD, "J", null, null).visitEnd();

        emitAllocHelper();
        emitStrHelper();
        emitNewstrHelper();
        emitClinit(ir);

        DefinedFunction mainFunction = null;
        for (DefinedFunction f : ir.definedFunctions()) {
            if (f.name().equals("main")) {
                mainFunction = f;
            }
            compileFunction(f);
        }
        emitDispatchers();

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
    // Static memory layout: globals + string literals get a fixed,
    // compile-time-known address, packed back to back (alignment is not
    // needed for correctness here: $buf is a heap ByteBuffer, which reads
    // and writes multi-byte values at any offset without requiring it).
    //

    /** Bytes to reserve for one variable's slot: exactly its true size.
     *  (An earlier version of this padded every scalar up to 8 bytes,
     *  to protect against writes wider than the variable's own type --
     *  see visit(Assign)'s doc comment for why that could otherwise
     *  happen. That padding is no longer needed now that a named-target
     *  assignment always truncates to the *entity's* own declared width
     *  rather than trusting the compiled RHS value's width, and it broke
     *  address arithmetic between adjacent variables, e.g. "(&y - &x)"
     *  no longer matching sizeof(x).) */
    private long slotSize(Entity e) {
        return e.allocSize();
    }

    private void computeStaticLayout(IR ir) {
        long offset = STATIC_BASE;
        for (DefinedVariable var : ir.scope().definedGlobalScopeVariables()) {
            globalAddr.put(var, offset);
            globalVarsInOrder.add(var);
            offset += slotSize(var);
        }
        for (ConstantEntry ent : ir.constantTable()) {
            stringAddr.put(ent, offset);
            stringLiteralsInOrder.add(ent);
            offset += encodeCString(ent.value()).length;
        }
        staticEnd = offset;
    }

    private byte[] encodeCString(String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[raw.length + 1];  // NUL-terminated, like a real C string
        System.arraycopy(raw, 0, result, 0, raw.length);
        return result;
    }

    private String baseName(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    //
    // <clinit>: set up $mem/$buf, populate string literals, run global
    // variable initializers, then initialize the stack/heap pointers.
    //

    private void emitClinit(IR ir) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        mv.visitLdcInsn(HEAP_SIZE);
        mv.visitIntInsn(NEWARRAY, T_BYTE);
        mv.visitFieldInsn(PUTSTATIC, className, MEM_FIELD, "[B");

        mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
        mv.visitMethodInsn(INVOKESTATIC, BUF_CLASS, "wrap", "([B)" + BUF_DESC, false);
        mv.visitFieldInsn(GETSTATIC, "java/nio/ByteOrder", "LITTLE_ENDIAN", "Ljava/nio/ByteOrder;");
        mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "order",
                "(Ljava/nio/ByteOrder;)" + BUF_DESC, false);
        mv.visitFieldInsn(PUTSTATIC, className, BUF_FIELD, BUF_DESC);

        for (ConstantEntry ent : stringLiteralsInOrder) {
            emitStaticBytesInit(mv, stringAddr.get(ent), encodeCString(ent.value()));
        }

        FunctionCompiler fc = new FunctionCompiler(mv);
        for (DefinedVariable var : globalVarsInOrder) {
            if (var.hasInitializer() && var.ir() != null) {
                fc.storeGlobalInit(globalAddr.get(var), var.location(), var.ir(),
                        asmWidthOf(var.type()));
            }
        }

        mv.visitLdcInsn((long) HEAP_SIZE);
        mv.visitFieldInsn(PUTSTATIC, className, SP_FIELD, "J");
        mv.visitLdcInsn(staticEnd);
        mv.visitFieldInsn(PUTSTATIC, className, HP_FIELD, "J");

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Copies a fixed byte[] into $mem at a fixed offset, by round-tripping
     *  it through a Latin-1 string constant (so it lands in the constant
     *  pool) and System.arraycopy -- much more compact than one
     *  instruction per byte. */
    private void emitStaticBytesInit(MethodVisitor mv, long addr, byte[] bytes) {
        if (bytes.length == 0) return;
        String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        mv.visitLdcInsn(latin1);
        mv.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "ISO_8859_1",
                "Ljava/nio/charset/Charset;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "getBytes",
                "(Ljava/nio/charset/Charset;)[B", false);
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
        mv.visitLdcInsn((int) addr);
        mv.visitLdcInsn(bytes.length);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
    }

    //
    // Runtime support methods, handwritten in bytecode (there is no
    // cflat source for them): $alloc bumps the heap pointer, $str reads a
    // NUL-terminated C string out of memory into a Java String, $newstr
    // does the reverse (used to build argv; see emitMainBridge).
    //

    private void emitAllocHelper() {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, ALLOC_METHOD, "(J)J", null, null);
        mv.visitCode();
        // long addr = $hp; $hp += size; return addr;
        mv.visitFieldInsn(GETSTATIC, className, HP_FIELD, "J");
        mv.visitVarInsn(LSTORE, 2);
        mv.visitFieldInsn(GETSTATIC, className, HP_FIELD, "J");
        mv.visitVarInsn(LLOAD, 0);
        mv.visitInsn(LADD);
        mv.visitFieldInsn(PUTSTATIC, className, HP_FIELD, "J");
        mv.visitVarInsn(LLOAD, 2);
        mv.visitInsn(LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitStrHelper() {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, STR_METHOD,
                "(J)Ljava/lang/String;", null, null);
        mv.visitCode();
        // slots: addr0=0,1 (long param); addr=2; end=3; bytes=4
        org.objectweb.asm.Label loopStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label loopEnd = new org.objectweb.asm.Label();
        mv.visitVarInsn(LLOAD, 0);
        mv.visitInsn(L2I);
        mv.visitVarInsn(ISTORE, 2);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitLabel(loopStart);
        mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(BALOAD);
        mv.visitJumpInsn(IFEQ, loopEnd);
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(GOTO, loopStart);
        mv.visitLabel(loopEnd);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ISUB);
        mv.visitIntInsn(NEWARRAY, T_BYTE);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ISUB);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        mv.visitTypeInsn(NEW, "java/lang/String");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>",
                "([BLjava/nio/charset/Charset;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitNewstrHelper() {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, NEWSTR_METHOD,
                "(Ljava/lang/String;)J", null, null);
        mv.visitCode();
        // slots: s=0 (ref param); bytes=1 (ref); addr=2,3 (long)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "getBytes",
                "(Ljava/nio/charset/Charset;)[B", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitInsn(I2L);
        mv.visitLdcInsn(1L);
        mv.visitInsn(LADD);
        mv.visitMethodInsn(INVOKESTATIC, className, ALLOC_METHOD, "(J)J", false);
        mv.visitVarInsn(LSTORE, 2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
        mv.visitVarInsn(LLOAD, 2);
        mv.visitInsn(L2I);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
        mv.visitVarInsn(LLOAD, 2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitInsn(I2L);
        mv.visitInsn(LADD);
        mv.visitInsn(L2I);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(LLOAD, 2);
        mv.visitInsn(LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    //
    // Functions
    //

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

    /** Emits a bridge "public static void main(String[])" that builds a
     *  real argv out of the JVM's own String[] args, calls the user's
     *  cflat main(), and translates its return value to System.exit. */
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
        // slots: args=0 (String[]); argc=1 (int); argv=2,3 (long); i=4 (int)
        if (params.size() >= 1) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, 1);
        }
        if (params.size() >= 2) {
            // argv = $alloc(argc * 8): an array of (argc) 8-byte pointers
            mv.visitVarInsn(ILOAD, 1);
            mv.visitInsn(I2L);
            mv.visitLdcInsn(8L);
            mv.visitInsn(LMUL);
            mv.visitMethodInsn(INVOKESTATIC, className, ALLOC_METHOD, "(J)J", false);
            mv.visitVarInsn(LSTORE, 2);
            // argv[0] = $newstr(<class name>), standing in for argv[0]
            mv.visitFieldInsn(GETSTATIC, className, BUF_FIELD, BUF_DESC);
            mv.visitVarInsn(LLOAD, 2);
            mv.visitInsn(L2I);
            mv.visitLdcInsn(className);
            mv.visitMethodInsn(INVOKESTATIC, className, NEWSTR_METHOD,
                    "(Ljava/lang/String;)J", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "putLong", "(IJ)" + BUF_DESC, false);
            mv.visitInsn(POP);
            // for (i = 0; i < args.length; i++) argv[i+1] = $newstr(args[i]);
            org.objectweb.asm.Label loopStart = new org.objectweb.asm.Label();
            org.objectweb.asm.Label loopEnd = new org.objectweb.asm.Label();
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 4);
            mv.visitLabel(loopStart);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitJumpInsn(IF_ICMPGE, loopEnd);
            mv.visitFieldInsn(GETSTATIC, className, BUF_FIELD, BUF_DESC);
            mv.visitVarInsn(LLOAD, 2);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IADD);
            mv.visitInsn(I2L);
            mv.visitLdcInsn(8L);
            mv.visitInsn(LMUL);
            mv.visitInsn(LADD);
            mv.visitInsn(L2I);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, className, NEWSTR_METHOD,
                    "(Ljava/lang/String;)J", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "putLong", "(IJ)" + BUF_DESC, false);
            mv.visitInsn(POP);
            mv.visitIincInsn(4, 1);
            mv.visitJumpInsn(GOTO, loopStart);
            mv.visitLabel(loopEnd);
        }
        if (params.size() >= 1) {
            mv.visitVarInsn(ILOAD, 1);
            if (paramOrReturnWidth(params.get(0).type()) == Width.LONG) {
                mv.visitInsn(I2L);
            }
        }
        if (params.size() >= 2) {
            mv.visitVarInsn(LLOAD, 2);
            if (paramOrReturnWidth(params.get(1).type()) != Width.LONG) {
                mv.visitInsn(L2I);
            }
        }
        mv.visitMethodInsn(INVOKESTATIC, className, "main", methodDescriptor(mainFunction), false);
        if (!mainFunction.isVoid()) {
            if (paramOrReturnWidth(mainFunction.returnType()) == Width.LONG) {
                mv.visitInsn(L2I);
            }
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private String methodDescriptor(Function f) {
        List<net.loveruby.cflat.type.Type> paramTypes = new ArrayList<net.loveruby.cflat.type.Type>();
        for (CBCParameter p : f.parameters()) {
            paramTypes.add(p.type());
        }
        return buildDescriptor(paramTypes, f.returnType());
    }

    private String signatureDescriptor(net.loveruby.cflat.type.FunctionType ft) {
        return buildDescriptor(ft.paramTypes(), ft.returnType());
    }

    /** Builds a JVM method descriptor for a cflat signature. A struct/union
     *  return type gets one extra trailing "J" parameter appended: see the
     *  class doc for the hidden-pointer convention this backend uses to
     *  pass/return them by value. */
    private String buildDescriptor(List<net.loveruby.cflat.type.Type> paramTypes,
            net.loveruby.cflat.type.Type returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (net.loveruby.cflat.type.Type t : paramTypes) {
            sb.append(paramOrReturnWidth(t).descriptor);
        }
        if (isAggregate(returnType)) {
            sb.append("J");
        }
        sb.append(")");
        sb.append(returnType.isVoid() ? "V" : paramOrReturnWidth(returnType).descriptor);
        return sb.toString();
    }

    private boolean isAggregate(net.loveruby.cflat.type.Type t) {
        return t.isStruct() || t.isUnion();
    }

    //
    // Function pointers: id assignment and dispatch tables
    //

    private void assignFunctionIds(IR ir) {
        for (DefinedFunction f : ir.definedFunctions()) {
            if (f.type().getFunctionType().isVararg()) {
                continue;  // never gets a body either (see compileFunction); can't be dispatched to
            }
            functionId.put(f, nextFunctionId++);
            String desc = methodDescriptor(f);
            List<DefinedFunction> group = functionsByDescriptor.get(desc);
            if (group == null) {
                group = new ArrayList<DefinedFunction>();
                functionsByDescriptor.put(desc, group);
            }
            group.add(f);
        }
    }

    private String dispatcherName(String funcDescriptor) {
        return "$call$" + funcDescriptor;
    }

    /** Emits, for one distinct function descriptor, a
     *  "(J<funcDescriptor's params>)<funcDescriptor's return>" method
     *  that looks its long id argument up in a lookup-switch over every
     *  matching function and jumps to a direct invokestatic -- the
     *  closest the JVM has to "calling through a function pointer". */
    private void emitDispatcher(String funcDescriptor, List<DefinedFunction> funcs) {
        TreeMap<Long, DefinedFunction> byId = new TreeMap<Long, DefinedFunction>();
        for (DefinedFunction f : funcs) {
            byId.put(functionId.get(f), f);
        }
        String dispatcherDesc = "(J" + funcDescriptor.substring(1);
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC,
                dispatcherName(funcDescriptor), dispatcherDesc, null, null);
        mv.visitCode();

        String params = funcDescriptor.substring(1, funcDescriptor.indexOf(')'));
        List<Integer> argSlots = new ArrayList<Integer>();
        List<Boolean> argWide = new ArrayList<Boolean>();
        int slot = 2;  // the id (long) occupies slots 0-1
        for (int i = 0; i < params.length(); i++) {
            boolean wide = params.charAt(i) == 'J';
            argSlots.add(slot);
            argWide.add(wide);
            slot += wide ? 2 : 1;
        }
        char retChar = funcDescriptor.charAt(funcDescriptor.length() - 1);

        int n = byId.size();
        int[] keys = new int[n];
        org.objectweb.asm.Label[] labels = new org.objectweb.asm.Label[n];
        List<DefinedFunction> ordered = new ArrayList<DefinedFunction>(byId.values());
        int i = 0;
        for (Long key : byId.keySet()) {
            keys[i] = key.intValue();
            labels[i] = new org.objectweb.asm.Label();
            i++;
        }
        org.objectweb.asm.Label dflt = new org.objectweb.asm.Label();

        mv.visitVarInsn(LLOAD, 0);
        mv.visitInsn(L2I);
        mv.visitLookupSwitchInsn(dflt, keys, labels);
        for (int c = 0; c < n; c++) {
            mv.visitLabel(labels[c]);
            for (int a = 0; a < argSlots.size(); a++) {
                mv.visitVarInsn(argWide.get(a) ? LLOAD : ILOAD, argSlots.get(a));
            }
            mv.visitMethodInsn(INVOKESTATIC, className, ordered.get(c).name(),
                    funcDescriptor, false);
            mv.visitInsn(retChar == 'V' ? RETURN : (retChar == 'J' ? LRETURN : IRETURN));
        }
        mv.visitLabel(dflt);
        mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("invalid function pointer value");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitDispatchers() {
        for (Map.Entry<String, List<DefinedFunction>> e : functionsByDescriptor.entrySet()) {
            emitDispatcher(e.getKey(), e.getValue());
        }
    }

    //
    // Type widths
    //

    /** The JVM-level category (int or long) a value must have at a
     *  function boundary (parameter/return): the only two shapes an
     *  actual JVM method call can carry.  An array parameter decays to a
     *  pointer per C rules; a struct/union parameter/return is, per this
     *  backend's hidden-pointer convention (see the class doc), also
     *  just an address. This is distinct from asmWidthOf(), which is the
     *  *true* C width (down to 1 byte) used for every memory access once
     *  a value is safely inside $mem. */
    private enum Width {
        INT(1, "I"), LONG(2, "J");

        final int slots;
        final String descriptor;

        Width(int slots, String descriptor) {
            this.slots = slots;
            this.descriptor = descriptor;
        }
    }

    private Width paramOrReturnWidth(net.loveruby.cflat.type.Type t) {
        if (t.isArray() || isAggregate(t)) {
            return Width.LONG;  // arrays decay to a pointer; struct/union are passed by address
        }
        return (t.size() == 8) ? Width.LONG : Width.INT;
    }

    /** The true C-level memory width of a scalar (never struct/union:
     *  those are only ever accessed by address, never loaded whole). */
    private net.loveruby.cflat.asm.Type asmWidthOf(net.loveruby.cflat.type.Type t) {
        if (t.isArray()) {
            return net.loveruby.cflat.asm.Type.INT64;  // decays to a pointer
        }
        if (!t.isScalar()) {
            return net.loveruby.cflat.asm.Type.INT64;  // already reported elsewhere; safe fallback
        }
        return net.loveruby.cflat.asm.Type.get(t.size());
    }

    private static boolean isWide(net.loveruby.cflat.asm.Type t) {
        return t == net.loveruby.cflat.asm.Type.INT64;
    }

    private static boolean isComparison(Op op) {
        switch (op) {
        case EQ: case NEQ: case S_GT: case S_GTEQ: case S_LT: case S_LTEQ:
        case U_GT: case U_GTEQ: case U_LT: case U_LTEQ:
            return true;
        default:
            return false;
        }
    }

    /** The width an expression's *value* actually occupies on the JVM
     *  stack once compiled. This is almost always just e.type(), except
     *  for comparisons and "!": TypeChecker types those as the promoted
     *  operand type (matching x86, where a comparison result just zero-
     *  extends into a possibly-wider register with no ill effect), which
     *  can be "long" or a pointer type even though the value emitted here
     *  is always a plain 0/1 int -- so callers that need to know whether
     *  to treat a compiled value as a JVM int or long (Return, ExprStmt,
     *  Assign, printf args, ...) must go through this rather than reading
     *  the IR node's own type directly. */
    private net.loveruby.cflat.asm.Type resultWidth(Expr e) {
        if (e instanceof Bin && isComparison(((Bin) e).op())) {
            return net.loveruby.cflat.asm.Type.INT32;
        }
        if (e instanceof Uni && ((Uni) e).op() == Op.NOT) {
            return net.loveruby.cflat.asm.Type.INT32;
        }
        if (e instanceof Var && e.type() == null) {
            // A Var whose entity is a struct/union or a bare function
            // name has no scalar asm.Type of its own (IRGenerator's
            // varType() returns null for non-scalar types), but its
            // compiled *value* here is always a long: an address for a
            // struct/union, a function-pointer id for a function.
            return net.loveruby.cflat.asm.Type.INT64;
        }
        return e.type();
    }

    //
    // Per-function / per-initializer compiler
    //

    private class FunctionCompiler implements IRVisitor<Void, Void> {
        private final MethodVisitor mv;
        private final DefinedFunction func;
        private final Map<Entity, Long> frameOffset = new HashMap<Entity, Long>();
        /** A struct/union parameter's frame "slot" is the incoming
         *  pointer itself (already the address of a fresh, caller-made
         *  copy -- see the class doc), not a spilled-to-memory copy of
         *  it, so its address is just this JVM local, loaded directly. */
        private final Map<Entity, Integer> indirectSlot = new HashMap<Entity, Integer>();
        private final Map<net.loveruby.cflat.asm.Label, org.objectweb.asm.Label> labels =
                new HashMap<net.loveruby.cflat.asm.Label, org.objectweb.asm.Label>();
        private final List<Integer> paramJvmSlots = new ArrayList<Integer>();
        private final List<Width> paramWidths = new ArrayList<Width>();
        private final long frameSize;
        private final int frameBaseSlot;
        private final int returnValueSlot;
        private final int switchScratchInt;
        private final int switchScratchLong;
        /** Scratch slots used to marshal a struct/union byte-for-byte copy
         *  (a by-value argument, a whole-value assignment, a struct/union
         *  return): the destination and source addresses are stashed here
         *  because System.arraycopy() needs [destAddr, srcAddr] in the
         *  opposite order from how convenient it is to *compute* them. */
        private final int copyDestScratch;
        private final int copySrcScratch;
        private final int hiddenOutputSlot;
        private final org.objectweb.asm.Label epilogueLabel = new org.objectweb.asm.Label();
        private Location currentLocation;

        /** For compiling a function body. */
        FunctionCompiler(MethodVisitor mv, DefinedFunction func) {
            this.mv = mv;
            this.func = func;
            this.currentLocation = func.location();

            int jvmSlot = 0;
            for (CBCParameter p : func.parameters()) {
                Width w = paramOrReturnWidth(p.type());
                paramJvmSlots.add(jvmSlot);
                paramWidths.add(w);
                jvmSlot += w.slots;
            }
            if (isAggregate(func.returnType())) {
                // Hidden trailing hand-off parameter: where to write the
                // struct/union result (see the class doc).
                hiddenOutputSlot = jvmSlot;
                jvmSlot += 2;
            }
            else {
                hiddenOutputSlot = -1;
            }

            long off = 0;
            List<CBCParameter> params = func.parameters();
            for (int i = 0; i < params.size(); i++) {
                CBCParameter p = params.get(i);
                if (isAggregate(p.type())) {
                    indirectSlot.put(p, paramJvmSlots.get(i));
                }
                else {
                    frameOffset.put(p, off);
                    off += slotSize(p);
                }
            }
            // func.lvarScope() is the function body's own scope; walking
            // it recursively (rather than flattening it with
            // allLocalVariables()) lets sibling block scopes -- whose
            // lifetimes never overlap -- share the same offsets, exactly
            // like the x86 backend's own locateLocalVariables().
            frameSize = layoutScope(func.lvarScope(), off);

            frameBaseSlot = jvmSlot;
            jvmSlot += 2;
            returnValueSlot = jvmSlot;
            jvmSlot += 2;
            switchScratchInt = jvmSlot++;
            switchScratchLong = jvmSlot;
            jvmSlot += 2;
            copyDestScratch = jvmSlot;
            jvmSlot += 2;
            copySrcScratch = jvmSlot;
        }

        /** Recursively lays out one block scope's own variables starting
         *  at parentLen, then each child scope again from that same
         *  point (not chained across siblings), returning the largest
         *  extent reached by any of them. */
        private long layoutScope(LocalScope scope, long parentLen) {
            long len = parentLen;
            for (DefinedVariable var : scope.localVariables()) {
                frameOffset.put(var, len);
                len += slotSize(var);
            }
            long maxLen = len;
            for (LocalScope child : scope.children()) {
                maxLen = Math.max(maxLen, layoutScope(child, len));
            }
            return maxLen;
        }

        /** For compiling a single top-level constant initializer expression
         *  (used from <clinit>): globals only, no frame of its own. */
        FunctionCompiler(MethodVisitor mv) {
            this.mv = mv;
            this.func = null;
            this.frameSize = 0;
            this.frameBaseSlot = -1;
            this.returnValueSlot = -1;
            this.switchScratchInt = -1;
            this.switchScratchLong = -1;
            this.copyDestScratch = -1;
            this.copySrcScratch = -1;
            this.hiddenOutputSlot = -1;
        }

        void run() {
            mv.visitCode();
            emitPrologue();
            for (Stmt s : func.ir()) {
                compileStmt(s);
            }
            emitEpilogue();
        }

        private void emitPrologue() {
            // frameBase = $sp - frameSize; $sp = frameBase;
            pushSp();
            mv.visitLdcInsn(frameSize);
            mv.visitInsn(LSUB);
            mv.visitInsn(DUP2);
            mv.visitVarInsn(LSTORE, frameBaseSlot);
            putSp();
            // spill incoming JVM parameters into their memory frame slot
            List<CBCParameter> params = func.parameters();
            for (int i = 0; i < params.size(); i++) {
                CBCParameter p = params.get(i);
                if (isAggregate(p.type())) {
                    // Its "frame slot" (indirectSlot) already holds the
                    // caller-supplied address directly (see the class
                    // doc's hidden-pointer convention); there is no
                    // simulated-memory copy of its own to spill into.
                    continue;
                }
                pushBuf();
                pushAddressOf(p);
                mv.visitInsn(L2I);
                mv.visitVarInsn(paramWidths.get(i) == Width.LONG ? LLOAD : ILOAD,
                        paramJvmSlots.get(i));
                emitStore(asmWidthOf(p.type()));
            }
        }

        private void emitEpilogue() {
            // Default return value for implicit fall-off-the-end (mirrors
            // C's undefined behavior with a well-defined 0, and gives the
            // epilogue a value to load unconditionally).
            if (!func.isVoid()) {
                boolean wide = isWide(asmWidthOf(func.returnType()));
                mv.visitInsn(wide ? LCONST_0 : ICONST_0);
                mv.visitVarInsn(wide ? LSTORE : ISTORE, returnValueSlot);
            }
            mv.visitLabel(epilogueLabel);
            // $sp = frameBase + frameSize
            mv.visitVarInsn(LLOAD, frameBaseSlot);
            mv.visitLdcInsn(frameSize);
            mv.visitInsn(LADD);
            putSp();
            if (func.isVoid()) {
                mv.visitInsn(RETURN);
            }
            else {
                boolean wide = isWide(asmWidthOf(func.returnType()));
                mv.visitVarInsn(wide ? LLOAD : ILOAD, returnValueSlot);
                mv.visitInsn(wide ? LRETURN : IRETURN);
            }
        }

        /** Compiles and stores a single global variable's initializer;
         *  used only from <clinit>, on the no-function FunctionCompiler. */
        void storeGlobalInit(long addr, Location loc, Expr initExpr,
                net.loveruby.cflat.asm.Type width) {
            currentLocation = loc;
            pushBuf();
            mv.visitLdcInsn(addr);
            mv.visitInsn(L2I);
            compile(initExpr);
            emitStore(width);
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
        // The simulated address space
        //

        private void pushSp() {
            mv.visitFieldInsn(GETSTATIC, className, SP_FIELD, "J");
        }

        private void putSp() {
            mv.visitFieldInsn(PUTSTATIC, className, SP_FIELD, "J");
        }

        private void pushBuf() {
            mv.visitFieldInsn(GETSTATIC, className, BUF_FIELD, BUF_DESC);
        }

        /** Pushes the (long) "address" of an entity: a compile-time
         *  constant for a global, frameBase-relative for a local/param,
         *  the incoming JVM slot itself for a struct/union parameter
         *  (see the class doc), or -- when e is a function -- its
         *  compile-time-assigned function-pointer id (the closest thing
         *  to an "address" a function has on the JVM; see the class doc's
         *  function-pointer section). */
        private void pushAddressOf(Entity e) {
            if (e instanceof Function) {
                pushFunctionId((Function) e);
                return;
            }
            Integer indirect = indirectSlot.get(e);
            if (indirect != null) {
                mv.visitVarInsn(LLOAD, indirect);
                return;
            }
            Long staticOff = globalAddr.get(e);
            if (staticOff != null) {
                mv.visitLdcInsn(staticOff);
                return;
            }
            Long off = frameOffset.get(e);
            if (off != null) {
                mv.visitVarInsn(LLOAD, frameBaseSlot);
                mv.visitLdcInsn(off);
                mv.visitInsn(LADD);
                return;
            }
            error("address of an unsupported/external variable: " + e.name());
            mv.visitInsn(LCONST_0);
        }

        /** Pushes a defined, non-vararg function's id as a long -- used
         *  both for &f and for a bare function name decaying to a value
         *  (e.g. "fp = f;"), which are the same value on this backend. */
        private void pushFunctionId(Function f) {
            if (!(f instanceof DefinedFunction) || f.type().getFunctionType().isVararg()) {
                error("cannot take the address of this function on the JVM backend: "
                        + f.name() + "()");
                mv.visitInsn(LCONST_0);
                return;
            }
            Long id = functionId.get((DefinedFunction) f);
            if (id == null) {
                error("internal error: no id assigned to function " + f.name() + "()");
                mv.visitInsn(LCONST_0);
                return;
            }
            mv.visitLdcInsn(id);
        }

        /** Copies `size` bytes of $mem from the address in copySrcScratch
         *  to the address in copyDestScratch -- callers must store both
         *  there first. This is the whole of what a struct/union "value"
         *  (a by-value argument, a whole-value assignment, or a
         *  struct/union return) actually is at this backend's level: a
         *  raw memcpy, exactly like C's own struct assignment semantics. */
        private void emitArraycopy(long size) {
            mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
            mv.visitVarInsn(LLOAD, copySrcScratch);
            mv.visitInsn(L2I);
            mv.visitFieldInsn(GETSTATIC, className, MEM_FIELD, "[B");
            mv.visitVarInsn(LLOAD, copyDestScratch);
            mv.visitInsn(L2I);
            mv.visitLdcInsn((int) size);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy",
                    "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        }

        /** Assumes [buf, index] are already on the stack; leaves the
         *  loaded value (properly sign/zero-extended per "signed"). */
        private void emitLoad(net.loveruby.cflat.asm.Type t, boolean signed) {
            switch (t) {
            case INT8:
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "get", "(I)B", false);
                if (!signed) { mv.visitLdcInsn(0xFF); mv.visitInsn(IAND); }
                break;
            case INT16:
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "getShort", "(I)S", false);
                if (!signed) { mv.visitLdcInsn(0xFFFF); mv.visitInsn(IAND); }
                break;
            case INT32:
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "getInt", "(I)I", false);
                break;
            case INT64:
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "getLong", "(I)J", false);
                break;
            }
        }

        /** Assumes [buf, index, value] are already on the stack (value
         *  matching the JVM int/long category for width t); truncates
         *  and stores exactly width t's bytes. Signedness doesn't matter
         *  for a store: only the low bits are ever physically written. */
        private void emitStore(net.loveruby.cflat.asm.Type t) {
            switch (t) {
            case INT8:
                mv.visitInsn(I2B);
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "put", "(IB)" + BUF_DESC, false);
                mv.visitInsn(POP);
                break;
            case INT16:
                mv.visitInsn(I2S);
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "putShort", "(IS)" + BUF_DESC, false);
                mv.visitInsn(POP);
                break;
            case INT32:
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "putInt", "(II)" + BUF_DESC, false);
                mv.visitInsn(POP);
                break;
            case INT64:
                mv.visitMethodInsn(INVOKEVIRTUAL, BUF_CLASS, "putLong", "(IJ)" + BUF_DESC, false);
                mv.visitInsn(POP);
                break;
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
                popValue(resultWidth(e));
            }
            return null;
        }

        /** True for a Var naming a struct/union entity directly (as
         *  opposed to a bare function name, which is also a non-scalar
         *  Var -- see resultWidth()'s doc comment -- but behaves like an
         *  ordinary long value, not a byte blob to copy). */
        private boolean isAggregateVar(Expr e) {
            return (e instanceof Var)
                    && !(((Var) e).entity() instanceof Function)
                    && !((Var) e).entity().type().isScalar();
        }

        /** True for a call whose result is a struct/union, whether a
         *  direct call or one made through a function pointer. */
        private boolean callReturnsAggregate(Call c) {
            if (c.isStaticCall()) {
                return isAggregate(c.function().returnType());
            }
            net.loveruby.cflat.type.FunctionType ft = indirectCallSignature(c);
            return ft != null && isAggregate(ft.returnType());
        }

        /** Resolves the FunctionType being called through, for a call
         *  through a plain function-pointer variable (the only shape of
         *  indirect call this backend supports -- see compileIndirectCall).
         *  Returns null for anything else (a computed/complex function-
         *  pointer expression), so callers can reject it cleanly. */
        private net.loveruby.cflat.type.FunctionType indirectCallSignature(Call c) {
            if (!(c.expr() instanceof Var)) {
                return null;
            }
            net.loveruby.cflat.type.Type t = ((Var) c.expr()).entity().type();
            if (!t.isPointer() || !t.baseType().isFunction()) {
                return null;
            }
            return t.baseType().getFunctionType();
        }

        public Void visit(Assign node) {
            if (node.rhs() instanceof Call && callReturnsAggregate((Call) node.rhs())) {
                // "s = f();": have f() write its result straight into s
                // rather than into scratch space and copying it over.
                compileCallInto((Call) node.rhs(), node.lhs());
                popValue(net.loveruby.cflat.asm.Type.INT64);
                return null;
            }
            if (isAggregateVar(node.rhs())) {
                // Whole-value struct/union assignment ("s1 = s2;") is
                // just a raw memcpy at this backend's level -- see
                // emitArraycopy's doc comment.
                Entity src = ((Var) node.rhs()).entity();
                compile(node.lhs());
                mv.visitVarInsn(LSTORE, copyDestScratch);
                pushAddressOf(src);
                mv.visitVarInsn(LSTORE, copySrcScratch);
                emitArraycopy(src.type().size());
                return null;
            }
            pushBuf();
            net.loveruby.cflat.asm.Type storeWidth;
            if (node.lhs() instanceof Addr) {
                // A plain "x = ..." assignment: we know the exact target
                // variable, so store at its true declared width, however
                // wide the compiled RHS value actually is (see
                // slotSize()'s doc comment: narrowing conversions don't
                // get a cast node, so e.g. "char c = 1000;" arrives here
                // as a full-width int and must still be truncated to 1
                // byte on the way into memory).
                Entity e = ((Addr) node.lhs()).entity();
                pushAddressOf(e);
                storeWidth = asmWidthOf(e.type());
            }
            else {
                // Assigning through a pointer/array/struct access: there
                // is no single named target to ask, so fall back to the
                // RHS's own (usually already-correct) width, same as for
                // a load through the equivalent Mem node.
                compile(node.lhs());
                storeWidth = resultWidth(node.rhs());
            }
            mv.visitInsn(L2I);
            compile(node.rhs());
            coerceWidth(resultWidth(node.rhs()), isWide(storeWidth));
            emitStore(storeWidth);
            return null;
        }

        public Void visit(CJump node) {
            compile(node.cond());
            org.objectweb.asm.Label thenLabel = getLabel(node.thenLabel());
            org.objectweb.asm.Label elseLabel = getLabel(node.elseLabel());
            if (isWide(resultWidth(node.cond()))) {
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
            boolean wide = isWide(resultWidth(node.cond()));
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
                if (isAggregate(func.returnType())) {
                    emitAggregateReturn(node.expr());
                }
                else {
                    compile(node.expr());
                    mv.visitVarInsn(isWide(resultWidth(node.expr())) ? LSTORE : ISTORE, returnValueSlot);
                }
            }
            mv.visitJumpInsn(GOTO, epilogueLabel);
            return null;
        }

        /** Copies a returned struct/union's bytes into the hidden output
         *  address the caller supplied (see the class doc), then "returns"
         *  that same address as this function's own JVM return value. */
        private void emitAggregateReturn(Expr expr) {
            if (!isAggregateVar(expr)) {
                error("this struct/union return expression is too complex for the "
                        + "JVM backend; return a plain variable instead");
                mv.visitVarInsn(LLOAD, hiddenOutputSlot);
                mv.visitVarInsn(LSTORE, returnValueSlot);
                return;
            }
            Entity src = ((Var) expr).entity();
            mv.visitVarInsn(LLOAD, hiddenOutputSlot);
            mv.visitVarInsn(LSTORE, copyDestScratch);
            pushAddressOf(src);
            mv.visitVarInsn(LSTORE, copySrcScratch);
            emitArraycopy(func.returnType().size());
            mv.visitVarInsn(LLOAD, hiddenOutputSlot);
            mv.visitVarInsn(LSTORE, returnValueSlot);
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
                coerceWidth(resultWidth(node.left()), wide);
                compile(node.right());
                coerceWidth(resultWidth(node.right()), wide);
                emitArith(op, wide);
                break;
            }
            case U_DIV: case U_MOD: {
                boolean wide = isWide(node.type());
                compile(node.left());
                coerceWidth(resultWidth(node.left()), wide);
                compile(node.right());
                coerceWidth(resultWidth(node.right()), wide);
                String owner = wide ? "java/lang/Long" : "java/lang/Integer";
                String desc = wide ? "(JJ)J" : "(II)I";
                String name = (op == Op.U_DIV) ? "divideUnsigned" : "remainderUnsigned";
                mv.visitMethodInsn(INVOKESTATIC, owner, name, desc, false);
                break;
            }
            case BIT_LSHIFT: case BIT_RSHIFT: case ARITH_RSHIFT: {
                boolean wide = isWide(node.type());
                compile(node.left());
                coerceWidth(resultWidth(node.left()), wide);
                compile(node.right());
                coerceToInt(resultWidth(node.right()));
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
                boolean operandWide = isWide(resultWidth(node.left()));
                compile(node.left());
                compile(node.right());
                coerceWidth(resultWidth(node.right()), operandWide);
                emitComparison(op, operandWide);
                break;
            }
            }
            return null;
        }

        /** Widens/narrows a just-compiled value from its actual width to
         *  match what the operation using it needs. IRGenerator's own
         *  internal lowering (e.g. the element-size*index multiplication
         *  built for array indexing) sometimes combines operands of
         *  different widths directly, unlike type-checked source
         *  expressions (where both sides of a binary op are always cast
         *  to a common type already). */
        private void coerceWidth(net.loveruby.cflat.asm.Type actual, boolean wantWide) {
            boolean actualWide = isWide(actual);
            if (wantWide && !actualWide) {
                mv.visitInsn(I2L);
            }
            else if (!wantWide && actualWide) {
                mv.visitInsn(L2I);
            }
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
                // The source value may come straight from a raw memory
                // read (e.g. Mem, or a Var of an unsigned narrow type)
                // and isn't guaranteed to already be properly extended,
                // so always normalize from its true width first...
                if (srcSize < 4) {
                    truncateInt(srcSize, srcSigned);
                }
                // ...then truncate further if narrowing.
                if (dstSize < srcSize) {
                    truncateInt(dstSize, srcSigned);
                }
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
                    truncateInt(srcSize, false);  // zero-extend to 32 bits first
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
            compileCallInto(node, null);
            return null;
        }

        private boolean callIsVoid(Call call) {
            if (call.isStaticCall()) {
                // putchar/puts/printf are all declared to return int in
                // stdio.hb, so the generic isVoid() check below already
                // says "not void" for them; compilePutchar/compilePuts/
                // compilePrintf always leave a (possibly dummy) int on
                // the stack to match.
                return call.function().isVoid();
            }
            net.loveruby.cflat.type.FunctionType ft = indirectCallSignature(call);
            return ft != null && ft.returnType().isVoid();
        }

        /** Compiles a call, either leaving its result on the stack (per
         *  the descriptor: an int/long, or nothing for void) when
         *  destAddrExpr is null, or -- when the call returns a struct/
         *  union and destAddrExpr is given -- writing the result directly
         *  into destAddrExpr's address instead of a fresh scratch buffer
         *  (used by visit(Assign) for "s = f();", to skip a redundant
         *  copy); the JVM return value is still left on the stack either
         *  way (see the class doc: a struct/union-returning function
         *  "returns" its hidden output address for convenience). */
        private void compileCallInto(Call node, Expr destAddrExpr) {
            if (!node.isStaticCall()) {
                compileIndirectCall(node, destAddrExpr);
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
                compileArg(arg);
            }
            if (isAggregate(df.returnType())) {
                pushAggregateDest(destAddrExpr, df.returnType().size());
            }
            mv.visitMethodInsn(INVOKESTATIC, className, df.name(),
                    methodDescriptor(df), false);
        }

        /** Calling through a function pointer: the JVM has no notion of a
         *  raw callable address, so this dispatches through a generated
         *  lookup-switch method (one per distinct signature -- see
         *  emitDispatcher) that maps the id back to a real invokestatic.
         *  Only a plain function-pointer variable is supported as the
         *  callee expression (see indirectCallSignature's doc comment). */
        private void compileIndirectCall(Call node, Expr destAddrExpr) {
            net.loveruby.cflat.type.FunctionType ft = indirectCallSignature(node);
            if (ft == null) {
                error("this indirect call is too complex for the JVM backend "
                        + "(only calling through a plain function-pointer variable "
                        + "is supported)");
                pushDummy(node.type());
                return;
            }
            if (ft.isVararg()) {
                error("variadic function pointer calls are not supported by the JVM backend");
                pushDummy(node.type());
                return;
            }
            String desc = signatureDescriptor(ft);
            if (!functionsByDescriptor.containsKey(desc)) {
                error("no function defined in this file matches this function "
                        + "pointer's signature; it can never hold a callable value");
                pushDummy(node.type());
                return;
            }
            // The dispatcher's own descriptor is "(J" + <ft's params...>,
            // i.e. the id comes *first* -- unlike the hidden hand-off
            // parameter for a direct aggregate-returning call (which is
            // appended *last*; see buildDescriptor) -- so it must be
            // pushed before the real arguments, not after.
            compile(node.expr());
            for (Expr arg : node.args()) {
                compileArg(arg);
            }
            if (isAggregate(ft.returnType())) {
                pushAggregateDest(destAddrExpr, ft.returnType().size());
            }
            mv.visitMethodInsn(INVOKESTATIC, className, dispatcherName(desc),
                    "(J" + desc.substring(1), false);
        }

        /** Compiles one call argument, copying a struct/union-valued one
         *  into a fresh scratch buffer first and passing *that* address
         *  (proper C by-value semantics: the callee must not be able to
         *  observe changes back in the caller's own copy). */
        private void compileArg(Expr arg) {
            if (!isAggregateVar(arg)) {
                compile(arg);
                return;
            }
            Entity src = ((Var) arg).entity();
            long size = src.type().size();
            mv.visitLdcInsn(size);
            mv.visitMethodInsn(INVOKESTATIC, className, ALLOC_METHOD, "(J)J", false);
            mv.visitVarInsn(LSTORE, copyDestScratch);
            pushAddressOf(src);
            mv.visitVarInsn(LSTORE, copySrcScratch);
            emitArraycopy(size);
            mv.visitVarInsn(LLOAD, copyDestScratch);
        }

        /** Pushes the hidden trailing destination address a struct/union-
         *  returning call needs (see the class doc): destAddrExpr's own
         *  address when the caller has a specific target in mind (e.g.
         *  "s = f();"), or a fresh scratch buffer otherwise (e.g. the
         *  result is only going to be read from immediately, or is
         *  discarded outright). */
        private void pushAggregateDest(Expr destAddrExpr, long size) {
            if (destAddrExpr != null) {
                compile(destAddrExpr);
            }
            else {
                mv.visitLdcInsn(size);
                mv.visitMethodInsn(INVOKESTATIC, className, ALLOC_METHOD, "(J)J", false);
            }
        }

        private void loadSystemOut() {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        }

        /** Compiles a char* expression and leaves a real Java String
         *  (read from $mem at run time) on the stack. */
        private void compileCString(Expr arg) {
            compile(arg);
            if (!isWide(arg.type())) {
                mv.visitInsn(I2L);
            }
            mv.visitMethodInsn(INVOKESTATIC, className, STR_METHOD,
                    "(J)Ljava/lang/String;", false);
        }

        private void compilePutchar(Call node) {
            Expr arg = node.args().get(0);
            loadSystemOut();
            compile(arg);
            coerceToInt(resultWidth(arg));
            mv.visitInsn(DUP_X1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "write", "(I)V", false);
        }

        private void compilePuts(Call node) {
            loadSystemOut();
            compileCString(node.args().get(0));
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println",
                    "(Ljava/lang/String;)V", false);
            // puts()/printf() are declared to return int (the real libc
            // semantics is a character count / EOF); tracking the real
            // count isn't worth it here, so always report success (0)
            // for whatever, rare, code actually looks at the result.
            mv.visitInsn(ICONST_0);
        }

        private void compilePrintf(Call node) {
            List<Expr> args = node.args();
            Expr fmtExpr = args.get(0);
            if (!(fmtExpr instanceof Str)) {
                error("printf() is only supported with a string literal format "
                        + "by the JVM backend");
                mv.visitInsn(ICONST_0);
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
                    mv.visitInsn(ICONST_0);
                    return;
                }
                i = j + 1;
            }
            flushLiteral(literal);
            mv.visitInsn(ICONST_0);
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
            boolean wide = isWide(resultWidth(arg));
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
            coerceToInt(resultWidth(arg));
            mv.visitInsn(I2C);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(C)V", false);
            return idx + 1;
        }

        private int emitPrintString(List<Expr> args, int idx) {
            if (idx >= args.size()) {
                error("not enough arguments for printf format");
                return idx;
            }
            loadSystemOut();
            compileCString(args.get(idx));
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print",
                    "(Ljava/lang/String;)V", false);
            return idx + 1;
        }

        public Void visit(Addr node) {
            pushAddressOf(node.entity());
            return null;
        }

        public Void visit(Mem node) {
            pushBuf();
            compile(node.expr());
            mv.visitInsn(L2I);
            // Signedness isn't available at this IR level (only the
            // width is); real C programs already wrap a narrow Mem load
            // in an explicit cast whenever the sign actually matters
            // (assignment, promotion, comparison, ...), so a plain
            // (Java-default, sign-extended) read here is safe -- see the
            // class doc.
            emitLoad(node.type(), true);
            return null;
        }

        public Void visit(Var node) {
            Entity e = node.entity();
            if (e instanceof Function) {
                // A bare function name used as a value (e.g. "fp = f;",
                // decaying like an array does) is the same value as
                // "&f" on this backend -- see pushAddressOf's doc comment.
                pushAddressOf(e);
                return null;
            }
            if (!e.type().isScalar()) {
                error("cannot use a struct/union/array value directly here on the JVM "
                        + "backend (as a plain expression outside a supported by-value "
                        + "position); use a pointer instead: " + e.name());
                mv.visitInsn(LCONST_0);
                return null;
            }
            pushBuf();
            pushAddressOf(e);
            mv.visitInsn(L2I);
            emitLoad(asmWidthOf(e.type()), e.type().isSigned());
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
            Long addr = stringAddr.get(node.entry());
            if (addr == null) {
                error("internal error: unresolved string literal");
                mv.visitInsn(LCONST_0);
                return null;
            }
            mv.visitLdcInsn(addr);
            return null;
        }
    }
}
