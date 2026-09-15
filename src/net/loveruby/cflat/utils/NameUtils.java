package net.loveruby.cflat.utils;

public class NameUtils {
    /** Turns an arbitrary string into a valid Java identifier by replacing
     *  every character that cannot appear in one with "_" (prefixing with
     *  "_" too when the string would otherwise start with a digit, etc).
     *  Used to derive a JVM class name from a source file's base name,
     *  which may contain characters (like "-") that C identifiers and
     *  file names allow but Java identifiers do not. */
    static public String toJavaIdentifier(String name) {
        StringBuilder buf = new StringBuilder();
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            buf.append('_');
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            buf.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return buf.toString();
    }
}
