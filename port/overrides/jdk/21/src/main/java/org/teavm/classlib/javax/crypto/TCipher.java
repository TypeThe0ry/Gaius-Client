package org.teavm.classlib.javax.crypto;

import org.teavm.classlib.java.security.TKey;
import org.teavm.classlib.java.security.spec.TAlgorithmParameterSpec;

public class TCipher {
    public static final int ENCRYPT_MODE = 1;
    public static final int DECRYPT_MODE = 2;
    public static final int WRAP_MODE = 3;
    public static final int UNWRAP_MODE = 4;

    private final String transformation;

    protected TCipher(String transformation) {
        this.transformation = transformation;
    }

    public static TCipher getInstance(String transformation) {
        return new TCipher(transformation);
    }

    public String getAlgorithm() {
        return transformation;
    }

    public void init(int opmode, TKey key) {
    }

    public void init(int opmode, TKey key, TAlgorithmParameterSpec params) {
    }

    public byte[] doFinal(byte[] input) {
        return input == null ? new byte[0] : input.clone();
    }
}
