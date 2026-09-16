package net.loveruby.cflat.sysdep.jvm;
import net.loveruby.cflat.sysdep.BinaryAssemblyCode;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Set;

/**
 * Wraps the bytes of a generated JVM class file.  There is no textual
 * assembly format for this target: toSource()/dump() only print a short
 * summary, since the real output is the binary class file returned by
 * toBytes() (see javap -c to disassemble it).
 */
public class JVMAssemblyCode implements BinaryAssemblyCode {
    private final String className;
    private final byte[] bytes;
    private final String nativeRuntimeSource;
    private final Set<String> nativeStubNames;

    public JVMAssemblyCode(String className, byte[] bytes) {
        this(className, bytes, null, Collections.<String>emptySet());
    }

    /** nativeRuntimeSource is the source of a "NativeRuntime.java" to
     *  write alongside the class file (null if the program called no
     *  external function needing one, the common case); nativeStubNames
     *  is exactly the set of names it stubs out -- see
     *  CodeGenerator#nativeRuntimeSource() and NativeRuntime's own
     *  generated class doc for what these are for. */
    public JVMAssemblyCode(String className, byte[] bytes, String nativeRuntimeSource,
            Set<String> nativeStubNames) {
        this.className = className;
        this.bytes = bytes;
        this.nativeRuntimeSource = nativeRuntimeSource;
        this.nativeStubNames = nativeStubNames;
    }

    public byte[] toBytes() {
        return bytes;
    }

    public String nativeRuntimeSource() {
        return nativeRuntimeSource;
    }

    public Set<String> nativeStubNames() {
        return nativeStubNames;
    }

    public String toSource() {
        return "; JVM class file for " + className
                + " (" + bytes.length + " bytes)\n"
                + "; Run `javap -c " + className
                + ".class` for a real disassembly.\n";
    }

    public void dump() {
        dump(System.out);
    }

    public void dump(PrintStream s) {
        s.print(toSource());
    }
}
