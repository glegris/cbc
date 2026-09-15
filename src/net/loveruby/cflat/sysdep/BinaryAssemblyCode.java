package net.loveruby.cflat.sysdep;

/**
 * An AssemblyCode whose real output is a binary artifact (e.g. a JVM class
 * file) rather than a text file that must be fed to an external assembler.
 */
public interface BinaryAssemblyCode extends AssemblyCode {
    byte[] toBytes();
}
