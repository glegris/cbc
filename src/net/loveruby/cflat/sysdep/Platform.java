package net.loveruby.cflat.sysdep;
import net.loveruby.cflat.type.TypeTable;
import net.loveruby.cflat.utils.ErrorHandler;

public interface Platform {
    TypeTable typeTable();
    CodeGenerator codeGenerator(CodeGeneratorOptions opts, ErrorHandler h);
    Assembler assembler(ErrorHandler h);
    Linker linker(ErrorHandler h);

    /** Extension of the file produced by the code generator
     *  (".s" for native assembly, ".class" for a JVM class file). */
    String compiledFileExtension();

    /** True if the compiled output still needs to be fed to an external
     *  assembler and linker (native targets).  False if the code generator
     *  already produces the final, directly runnable artifact (JVM target). */
    boolean needsExternalToolchain();
}
