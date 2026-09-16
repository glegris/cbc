package net.loveruby.cflat.sysdep.jvm.runtime;

/** Thrown by a NativeRuntime stub for an external function this compiler
 *  doesn't know how to implement -- see NativeRuntime's own generated
 *  class doc. Compiling and running a program that calls such a function
 *  still works fine as long as that particular call is never reached;
 *  only actually calling it fails, with this exception. */
public class NotImplementedException extends RuntimeException {
    public NotImplementedException(String functionName) {
        super("native function not implemented: " + functionName + "() "
                + "-- edit NativeRuntime.java to implement it");
    }
}
