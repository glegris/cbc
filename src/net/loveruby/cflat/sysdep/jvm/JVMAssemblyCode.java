package net.loveruby.cflat.sysdep.jvm;
import net.loveruby.cflat.sysdep.BinaryAssemblyCode;
import java.io.PrintStream;

/**
 * Wraps the bytes of a generated JVM class file.  There is no textual
 * assembly format for this target: toSource()/dump() only print a short
 * summary, since the real output is the binary class file returned by
 * toBytes() (see javap -c to disassemble it).
 */
public class JVMAssemblyCode implements BinaryAssemblyCode {
    private final String className;
    private final byte[] bytes;

    public JVMAssemblyCode(String className, byte[] bytes) {
        this.className = className;
        this.bytes = bytes;
    }

    public byte[] toBytes() {
        return bytes;
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
