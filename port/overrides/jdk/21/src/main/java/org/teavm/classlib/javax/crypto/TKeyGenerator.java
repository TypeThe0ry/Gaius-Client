package org.teavm.classlib.javax.crypto;

import org.teavm.classlib.javax.crypto.spec.TSecretKeySpec;

public class TKeyGenerator {
    private final String algorithm;
    private int bits = 128;

    protected TKeyGenerator(String algorithm) {
        this.algorithm = algorithm;
    }

    public static TKeyGenerator getInstance(String algorithm) {
        return new TKeyGenerator(algorithm);
    }

    public void init(int bits) {
        this.bits = bits;
    }

    public TSecretKey generateKey() {
        return new TSecretKeySpec(new byte[Math.max(1, bits / 8)], algorithm);
    }
}
