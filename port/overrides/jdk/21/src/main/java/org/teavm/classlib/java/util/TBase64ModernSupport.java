package org.teavm.classlib.java.util;

public final class TBase64ModernSupport {
    private TBase64ModernSupport() {
    }

    public static TBase64.Encoder getMimeEncoder(int lineLength, byte[] lineSeparator) {
        // PEM parsing only needs the standard alphabet. Line wrapping is performed by callers.
        return TBase64.getEncoder();
    }
}
