package net.loveruby.cflat.sysdep.jvm.runtime;

/** Thrown by a NativeLibrary stub for an external function this compiler
 *  doesn't know how to implement -- see NativeLibrary's own generated
 *  class doc. Compiling and running a program that calls such a function
 *  still works fine as long as that particular call is never reached;
 *  only actually calling it fails, with this exception. */
public class NotImplementedException extends RuntimeException {
    public NotImplementedException(String functionName) {
        super("native function not implemented: " + functionName + "() "
                + "-- edit NativeLibrary.java to implement it");
    }
}
