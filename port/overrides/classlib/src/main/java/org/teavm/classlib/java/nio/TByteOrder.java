package org.teavm.classlib.java.nio;

/** Browser JavaScript and WebAssembly targets are little-endian. */
public final class TByteOrder {
    public static final TByteOrder BIG_ENDIAN = new TByteOrder("BIG_ENDIAN");
    public static final TByteOrder LITTLE_ENDIAN = new TByteOrder("LITTLE_ENDIAN");

    private final String name;

    private TByteOrder(String name) {
        this.name = name;
    }

    public static TByteOrder nativeOrder() {
        return LITTLE_ENDIAN;
    }

    @Override
    public String toString() {
        return name;
    }
}
