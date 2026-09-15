package net.loveruby.cflat.sysdep.jvm;
import net.loveruby.cflat.sysdep.Platform;
import net.loveruby.cflat.sysdep.Assembler;
import net.loveruby.cflat.sysdep.Linker;
import net.loveruby.cflat.sysdep.CodeGeneratorOptions;
import net.loveruby.cflat.type.TypeTable;
import net.loveruby.cflat.utils.ErrorHandler;

/**
 * Target platform which compiles cflat programs directly to a JVM class
 * file using the ASM bytecode library, instead of native x86 assembly.
 *
 * There is no separate assemble/link step: the CodeGenerator already
 * produces the final, directly runnable ".class" file.
 */
public class JVMPlatform implements Platform {
    public TypeTable typeTable() {
        // cflat "int" -> JVM int (4 bytes), cflat "long" -> JVM long (8
        // bytes).  Pointer values have no real representation on the JVM;
        // they are only carried around as opaque 8-byte handles.
        return TypeTable.lp64();
    }

    public net.loveruby.cflat.sysdep.CodeGenerator codeGenerator(
            CodeGeneratorOptions opts, ErrorHandler h) {
        return new CodeGenerator(h);
    }

    public Assembler assembler(ErrorHandler h) {
        throw new UnsupportedOperationException(
                "the JVM target does not use an external assembler");
    }

    public Linker linker(ErrorHandler h) {
        throw new UnsupportedOperationException(
                "the JVM target does not use an external linker");
    }

    public String compiledFileExtension() {
        return ".class";
    }

    public boolean needsExternalToolchain() {
        return false;
    }
}
