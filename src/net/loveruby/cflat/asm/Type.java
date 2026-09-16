package net.loveruby.cflat.asm;

public enum Type {
    INT8, INT16, INT32, INT64, FLOAT32, FLOAT64;

    static public Type get(long size) {
        switch ((int)size) {
        case 1:
            return INT8;
        case 2:
            return INT16;
        case 4:
            return INT32;
        case 8:
            return INT64;
        default:
            throw new Error("unsupported asm type size: " + size);
        }
    }

    /** Like get(), but for a floating-point value: 4 and 8 bytes are
     *  FLOAT32/FLOAT64 here, not the INT32/INT64 that get() would return
     *  for the same byte size -- the two families need distinct enum
     *  values because they need entirely different instructions despite
     *  sharing a size (e.g. FADD vs IADD on the JVM). */
    static public Type getFloat(long size) {
        switch ((int)size) {
        case 4:
            return FLOAT32;
        case 8:
            return FLOAT64;
        default:
            throw new Error("unsupported float asm type size: " + size);
        }
    }

    public boolean isFloat() {
        return this == FLOAT32 || this == FLOAT64;
    }

    public int size() {
        switch (this) {
        case INT8:
            return 1;
        case INT16:
            return 2;
        case INT32:
            return 4;
        case INT64:
            return 8;
        case FLOAT32:
            return 4;
        case FLOAT64:
            return 8;
        default:
            throw new Error("must not happen");
        }
    }
}
