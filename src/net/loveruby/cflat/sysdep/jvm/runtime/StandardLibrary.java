package net.loveruby.cflat.sysdep.jvm.runtime;

/**
 * Hand-written Java implementations of external functions callable from a
 * cflat program compiled with -arch=jvm, shared by every compiled program
 * (unlike NativeLibrary, generated fresh per program -- see its own
 * generated class doc). To make a function available everywhere without
 * regenerating anything, add a "public static" method here.
 *
 * A method's JVM signature must match how CodeGenerator#buildDescriptor
 * maps the cflat extern declaration's parameter/return types: char/short/
 * int/enum/_Bool -> JVM int, long/pointer (and a hidden trailing pointer
 * for a struct/union return) -> JVM long, float/double -> their JVM
 * equivalents, void return -> void.
 *
 * CodeGenerator keeps its own STANDARD_LIBRARY_FUNCTIONS set of names
 * already implemented here (a plain hardcoded list of names, deliberately
 * not found by reflecting over this class -- see that field's own doc
 * comment for why) so it knows to skip generating a NativeLibrary stub for
 * a name added here. Update that set too when adding a method.
 */
public class StandardLibrary {
}
